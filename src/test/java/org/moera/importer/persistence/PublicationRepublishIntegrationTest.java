package org.moera.importer.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moera.importer.application.PublicationProcessor;
import org.moera.importer.application.Sha256;
import org.moera.importer.model.BodyFormat;
import org.moera.importer.moera.MoeraOutcomeUnknownException;
import org.moera.importer.moera.MoeraService;
import org.moera.importer.moera.MoeraTemporaryException;
import org.moera.importer.source.ForwardAttribution;
import org.moera.importer.source.SourceMessage;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.moera.importer.persistence.jooq.enums.AccessState.GRANTED;
import static org.moera.importer.persistence.jooq.enums.SourceType.TELEGRAM;
import static org.moera.importer.persistence.jooq.enums.SubscriptionState.ACTIVE;
import static org.moera.importer.persistence.jooq.tables.Channels.CHANNELS;
import static org.moera.importer.persistence.jooq.tables.Destinations.DESTINATIONS;
import static org.moera.importer.persistence.jooq.tables.Mirrors.MIRRORS;
import static org.moera.importer.persistence.jooq.tables.Publications.PUBLICATIONS;
import static org.moera.importer.persistence.jooq.tables.PublicationStories.PUBLICATION_STORIES;

@SpringBootTest
class PublicationRepublishIntegrationTest extends PostgresIntegrationTest {

    private static final Instant START = Instant.parse("2026-10-01T12:00:00Z");
    private static final String FIRST_URI = "https://t.me/forwarding/10";
    private static final String SECOND_URI = "https://t.me/forwarding/11";
    private static final Map<String, String> STORIES = Map.of("timeline", "story-1", "news", "story-2");

    @Inject
    private DSLContext dslContext;

    @Inject
    private MessageRepository messageRepository;

    @Inject
    private PublicationRepository publicationRepository;

    @Inject
    private PublicationRepublishRepository publicationRepublishRepository;

    @Inject
    private PublicationProcessor publicationProcessor;

    @MockitoSpyBean
    private Clock clock;

    @MockitoBean
    private MoeraService moeraService;

    private Instant now;
    private long originalChannel;
    private long forwardingChannel;
    private long forwardingMirror;
    private long originalPublication;

    @BeforeEach
    void setUp() {
        now = START;
        when(clock.instant()).thenAnswer(ignored -> now);
        dslContext.truncate(CHANNELS).restartIdentity().cascade().execute();
        dslContext.truncate(DESTINATIONS).restartIdentity().cascade().execute();
        originalChannel = channel("-1001234567890", "original");
        forwardingChannel = channel("-1009876543210", "forwarding");
        long originalMirror = mirror(originalChannel, destination("original_0"));
        forwardingMirror = mirror(forwardingChannel, destination("forwarding_0"));
        ingest(originalChannel, 1, null, "Original 1");
        ingest(originalChannel, 2, null, "Original 2");
        publicationRepository.create(originalMirror, messageRepository.findUnassignedForMirror(originalMirror));
        originalPublication = dslContext.select(PUBLICATIONS.ID).from(PUBLICATIONS)
            .where(PUBLICATIONS.MIRROR_ID.eq(originalMirror)).fetchSingle(PUBLICATIONS.ID);
        dslContext.update(PUBLICATIONS).set(PUBLICATIONS.DESTINATION_POST_ID, "original-post")
            .set(PUBLICATIONS.PUBLISHED_REVISION, 0).setNull(PUBLICATIONS.NEXT_ATTEMPT_AT)
            .where(PUBLICATIONS.ID.eq(originalPublication)).execute();
    }

    @Test
    void removesOneUrlFromKnownStoriesEvenWhenAnotherFeedIsMissingAndDiscoveryFails() {
        when(moeraService.findRepublishedStories(any(), anyString(), anyString(), anyList(), anyList()))
            .thenReturn(Map.of("timeline", "story-1"));
        long owner = forward(10, 1);
        forward(11, 2);
        tick();
        messageRepository.markDeleted(forwardingChannel, Long.toString(10L << 20));
        when(moeraService.findRepublishedStories(any(), anyString(), anyString(), anyList(), anyList()))
            .thenThrow(new MoeraTemporaryException("Lookup unavailable", null));

        tick();

        verify(moeraService).updateStorySources(any(), eq("story-1"), eq(List.of(SECOND_URI)));
        now = START.plus(Duration.ofMinutes(30));
        tick();
        assertThat(dslContext.selectFrom(PUBLICATIONS).where(PUBLICATIONS.ID.eq(owner)).fetchSingle()
            .getLastError()).contains("30 minutes");
    }

    @Test
    void republishLifecycleReusesStoriesAndDeletesUrlsIndividually() {
        long owner = forward(10, 1);
        tick();
        var pending = dslContext.selectFrom(PUBLICATIONS).where(PUBLICATIONS.ID.eq(owner)).fetchSingle();
        assertThat(pending.getRepublishRequestedAt().toInstant()).isEqualTo(START);
        assertThat(pending.getRepublishLookupUris()).containsExactly(FIRST_URI);
        assertThat(pending.getNextAttemptAt().toInstant()).isEqualTo(START.plusSeconds(15));
        assertThat(pending.getDestinationPostId()).isNull();

        found();
        now = now.plusSeconds(15);
        tick();
        assertThat(stories(owner)).isEqualTo(STORIES);
        assertThat(publicationRepository.loadContext(owner, 0).destinationPostId()).isNull();

        forward(11, 2);
        tick();
        verify(moeraService).updateStorySources(any(), eq("story-1"), eq(List.of(FIRST_URI, SECOND_URI)));
        verify(moeraService, times(1)).republish(any(), eq("original_0"), eq("original-post"), anyList());

        ingest(forwardingChannel, 11, 2L, "Edited forward");
        assertThat(publicationRepository.claimReady(1, Duration.ofMinutes(1))).isEmpty();

        messageRepository.markDeleted(forwardingChannel, Long.toString(10L << 20));
        tick();
        verify(moeraService).updateStorySources(any(), eq("story-1"), eq(List.of(SECOND_URI)));

        messageRepository.markDeleted(forwardingChannel, Long.toString(11L << 20));
        tick();
        verify(moeraService).deleteStory(any(), eq("story-1"));
        verify(moeraService).deleteStory(any(), eq("story-2"));
        assertThat(stories(owner)).isEmpty();
        assertThat(dslContext.selectFrom(PUBLICATIONS).where(PUBLICATIONS.ID.eq(owner)).fetchSingle()
            .getNextAttemptAt()).isNull();
        verify(moeraService, never()).deletePost(any(), anyString());
        verify(moeraService, never()).updatePost(any(), anyString(), any());
        assertThat(publicationRepository.loadContext(originalPublication, 0).members())
            .extracting(member -> member.message().externalSourceUri())
            .containsExactly("https://t.me/original/1", "https://t.me/original/2");
    }

    @Test
    void deletionDuringDiscoveryStillFindsAndDeletesTheRequestedStories() {
        long owner = forward(10, 1);
        tick();
        messageRepository.markDeleted(forwardingChannel, Long.toString(10L << 20));
        found();

        tick();

        verify(moeraService, times(2)).findRepublishedStories(any(), eq("original_0"), eq("original-post"),
            eq(List.of(FIRST_URI)), eq(List.of("timeline", "news")));
        verify(moeraService).deleteStory(any(), eq("story-1"));
        assertThat(stories(owner)).isEmpty();
        assertThat(dslContext.selectFrom(PUBLICATIONS).where(PUBLICATIONS.ID.eq(owner)).fetchSingle()
            .getPublishedRevision()).isEqualTo(1);
    }

    @Test
    void unknownRequestOutcomeResumesFromDurableIntentWithoutRepeatingRepublish() {
        long owner = forward(10, 1);
        doThrow(new MoeraOutcomeUnknownException("Response lost", null)).when(moeraService)
            .republish(any(), anyString(), anyString(), anyList());
        tick();
        found();
        now = now.plusSeconds(15);

        tick();

        verify(moeraService, times(1)).republish(any(), anyString(), anyString(), anyList());
        assertThat(stories(owner)).isEqualTo(STORIES);
    }

    @Test
    void finishesInterruptedDeletionBeforeRepublishingALaterForward() {
        found();
        long owner = forward(10, 1);
        tick();
        var deleted = new HashSet<String>();
        var interrupted = new AtomicBoolean();
        doAnswer(invocation -> {
            if (deleted.size() == 1 && interrupted.compareAndSet(false, true)) {
                throw new MoeraTemporaryException("Deletion interrupted", null);
            }
            deleted.add(invocation.getArgument(1, String.class));
            return null;
        }).when(moeraService).deleteStory(any(), anyString());
        messageRepository.markDeleted(forwardingChannel, Long.toString(10L << 20));
        tick();
        assertThat(deleted).hasSize(1);
        assertThat(dslContext.fetchOne("select to_jsonb(p) ->> 'republish_deleting' from publications p where id = ?",
            owner).get(0, String.class)).isEqualTo("true");
        forward(11, 2);

        tick();

        assertThat(deleted).containsExactlyInAnyOrder("story-1", "story-2");
        assertThat(stories(owner)).isEmpty();
        when(moeraService.findRepublishedStories(any(), anyString(), anyString(), anyList(), anyList()))
            .thenReturn(Map.of("timeline", "new-story-1", "news", "new-story-2"));
        tick();
        verify(moeraService, times(2)).republish(any(), anyString(), anyString(), anyList());
        verify(moeraService).updateStorySources(any(), eq("new-story-1"), eq(List.of(SECOND_URI)));
        verify(moeraService, never()).updateStorySources(any(), eq("story-1"), eq(List.of(SECOND_URI)));
    }

    @Test
    void deletesDiscoveredStoriesWhileWaitingForMissingFeedsAndRetainsTimeoutDiagnostic() {
        when(moeraService.findRepublishedStories(any(), anyString(), anyString(), anyList(), anyList()))
            .thenReturn(Map.of("timeline", "story-1"));
        long owner = forward(10, 1);
        tick();
        messageRepository.markDeleted(forwardingChannel, Long.toString(10L << 20));

        tick();

        verify(moeraService).deleteStory(any(), eq("story-1"));
        var waiting = dslContext.selectFrom(PUBLICATIONS).where(PUBLICATIONS.ID.eq(owner)).fetchSingle();
        assertThat(waiting.getRepublishLookupUris()).containsExactly(FIRST_URI);
        assertThat(waiting.getNextAttemptAt()).isNotNull();
        now = START.plus(Duration.ofMinutes(30));
        tick();
        var timedOut = dslContext.selectFrom(PUBLICATIONS).where(PUBLICATIONS.ID.eq(owner)).fetchSingle();
        assertThat(timedOut.getNextAttemptAt()).isNull();
        assertThat(timedOut.getLastError()).contains("30 minutes");
    }

    @Test
    void cleanupFailuresHonorDiscoveryDeadlineThenUseBackoff() {
        when(moeraService.findRepublishedStories(any(), anyString(), anyString(), anyList(), anyList()))
            .thenReturn(Map.of("timeline", "story-1"));
        long owner = forward(10, 1);
        tick();
        messageRepository.markDeleted(forwardingChannel, Long.toString(10L << 20));
        now = START.plus(Duration.ofMinutes(30)).minusMillis(1);
        doThrow(new MoeraTemporaryException("Deletion unavailable", null)).when(moeraService)
            .deleteStory(any(), anyString());

        tick();
        assertThat(dslContext.selectFrom(PUBLICATIONS).where(PUBLICATIONS.ID.eq(owner)).fetchSingle()
            .getNextAttemptAt().toInstant()).isEqualTo(START.plus(Duration.ofMinutes(30)));
        now = START.plus(Duration.ofMinutes(30));
        tick();

        var row = dslContext.selectFrom(PUBLICATIONS).where(PUBLICATIONS.ID.eq(owner)).fetchSingle();
        assertThat(row.getNextAttemptAt().toInstant()).isAfter(now);
        assertThat(row.getLastError()).isEqualTo("Deletion unavailable");
        verify(moeraService, times(1)).findRepublishedStories(any(), anyString(), anyString(), anyList(), anyList());
    }

    @Test
    void discoveryFailureDoesNotPreventDeletingAlreadyKnownStories() {
        when(moeraService.findRepublishedStories(any(), anyString(), anyString(), anyList(), anyList()))
            .thenReturn(Map.of("timeline", "story-1"));
        forward(10, 1);
        tick();
        messageRepository.markDeleted(forwardingChannel, Long.toString(10L << 20));
        when(moeraService.findRepublishedStories(any(), anyString(), anyString(), anyList(), anyList()))
            .thenThrow(new MoeraTemporaryException("Lookup unavailable", null));

        tick();

        verify(moeraService).deleteStory(any(), eq("story-1"));
    }

    @Test
    void recordsIntentAndPartialStoriesConditionallyAndClampsRetryToDeadline() {
        long owner = forward(10, 1);
        PublicationClaim claim = publicationRepository.claimReady(1, Duration.ofMinutes(1)).getFirst();
        UUID stale = UUID.randomUUID();
        assertThat(publicationRepublishRepository.recordRequest(owner, stale, now, List.of(FIRST_URI))).isFalse();
        assertThat(publicationRepublishRepository.recordRequest(owner, claim.processingToken(), now,
            List.of(FIRST_URI))).isTrue();
        assertThat(publicationRepublishRepository.saveStories(owner, stale, STORIES)).isFalse();
        assertThat(publicationRepublishRepository.saveStories(owner, claim.processingToken(),
            Map.of("timeline", "story-1"))).isTrue();
        assertThat(publicationRepublishRepository.awaitStories(owner, stale, now)).isFalse();
        assertThat(publicationRepublishRepository.clearRequest(owner, stale)).isFalse();
        publicationRepository.retry(owner, claim.processingToken(), claim.targetRevision(),
            now.plus(Duration.ofHours(1)), "Lookup failed", null);

        var row = dslContext.selectFrom(PUBLICATIONS).where(PUBLICATIONS.ID.eq(owner)).fetchSingle();
        assertThat(row.getNextAttemptAt().toInstant()).isEqualTo(now.plus(Duration.ofMinutes(30)));
        assertThat(row.getRepublishLookupUris()).containsExactly(FIRST_URI);
        assertThat(stories(owner)).containsOnlyKeys("timeline");
    }

    @Test
    void recoversAnExpiredLeaseByPollingAndStopsAtThePersistedDeadline() {
        long owner = forward(10, 1);
        PublicationClaim claim = publicationRepository.claimReady(1, Duration.ofMinutes(1)).getFirst();
        publicationRepublishRepository.recordRequest(owner, claim.processingToken(), now, List.of(FIRST_URI));
        now = now.plusSeconds(61);
        tick();
        verify(moeraService, never()).republish(any(), anyString(), anyString(), anyList());
        now = START.plus(Duration.ofMinutes(30));

        tick();

        var row = dslContext.selectFrom(PUBLICATIONS).where(PUBLICATIONS.ID.eq(owner)).fetchSingle();
        assertThat(row.getNextAttemptAt()).isNull();
        assertThat(row.getLastError()).contains("30 minutes");
        assertThat(row.getPublishedRevision()).isNull();
    }

    @Test
    void discoveryErrorsCannotPostponeTheThirtyMinuteLimit() {
        long owner = forward(10, 1);
        when(moeraService.findRepublishedStories(any(), anyString(), anyString(), anyList(), anyList()))
            .thenThrow(new MoeraTemporaryException("Lookup unavailable", null));
        tick();
        now = START.plus(Duration.ofMinutes(29)).plusSeconds(59);
        tick();

        assertThat(dslContext.selectFrom(PUBLICATIONS).where(PUBLICATIONS.ID.eq(owner)).fetchSingle()
            .getNextAttemptAt().toInstant()).isEqualTo(START.plus(Duration.ofMinutes(30)));
        now = START.plus(Duration.ofMinutes(30));
        tick();
        assertThat(dslContext.selectFrom(PUBLICATIONS).where(PUBLICATIONS.ID.eq(owner)).fetchSingle()
            .getNextAttemptAt()).isNull();
    }

    private void found() {
        when(moeraService.findRepublishedStories(any(), anyString(), anyString(), anyList(), anyList()))
            .thenReturn(STORIES);
    }

    private void tick() {
        var claims = publicationRepository.claimReady(1, Duration.ofMinutes(1));
        assertThat(claims).hasSize(1);
        publicationProcessor.process(claims.getFirst());
    }

    private long forward(long id, long originalId) {
        ingest(forwardingChannel, id, originalId, "Forward");
        publicationRepository.create(forwardingMirror, messageRepository.findUnassignedForMirror(forwardingMirror));
        return dslContext.select(PUBLICATIONS.ID).from(PUBLICATIONS)
            .where(PUBLICATIONS.MIRROR_ID.eq(forwardingMirror)).and(PUBLICATIONS.REPUBLISH_OWNER_ID.isNull())
            .fetchSingle(PUBLICATIONS.ID);
    }

    private void ingest(long channelId, long id, Long originalId, String body) {
        var data = JsonNodeFactory.instance.objectNode();
        if (originalId != null) {
            data.put("forwardOriginChatId", "-1001234567890");
            data.put("forwardOriginMessageId", Long.toString(originalId << 20));
        }
        messageRepository.ingest(channelId, new SourceMessage(Long.toString(id << 20), false, false,
            START.minusSeconds(100 - id), body, BodyFormat.HTML,
            Sha256.digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "https://t.me/" + (originalId != null ? "forwarding" : "original") + "/" + id,
            originalId != null ? new ForwardAttribution("Original", "https://t.me/original/" + originalId) : null,
            data, List.of(), List.of()));
    }

    private long channel(String id, String name) {
        return dslContext.insertInto(CHANNELS).set(CHANNELS.SOURCE_TYPE, TELEGRAM)
            .set(CHANNELS.SOURCE_ID, id).set(CHANNELS.SOURCE_URI, "https://t.me/" + name)
            .set(CHANNELS.PUBLIC_USERNAME, name).set(CHANNELS.ACCESS_STATE, GRANTED)
            .set(CHANNELS.SUBSCRIPTION_STATE, ACTIVE).returning(CHANNELS.ID).fetchSingle().getId();
    }

    private long destination(String name) {
        return dslContext.insertInto(DESTINATIONS).set(DESTINATIONS.NODE_NAME, name)
            .set(DESTINATIONS.PROFILE_SYNC, false).returning(DESTINATIONS.ID).fetchSingle().getId();
    }

    private long mirror(long channel, long destination) {
        return dslContext.insertInto(MIRRORS).set(MIRRORS.CHANNEL_ID, channel).set(MIRRORS.DESTINATION_ID, destination)
            .set(MIRRORS.FEEDS, new String[] { "timeline", "news" }).returning(MIRRORS.ID).fetchSingle().getId();
    }

    private Map<String, String> stories(long owner) {
        return dslContext.select(PUBLICATION_STORIES.FEED_NAME, PUBLICATION_STORIES.STORY_ID).from(PUBLICATION_STORIES)
            .where(PUBLICATION_STORIES.PUBLICATION_ID.eq(owner))
            .fetchMap(PUBLICATION_STORIES.FEED_NAME, PUBLICATION_STORIES.STORY_ID);
    }

}
