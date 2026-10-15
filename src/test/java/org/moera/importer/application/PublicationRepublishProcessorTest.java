package org.moera.importer.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.moera.importer.model.BodyFormat;
import org.moera.importer.model.Destination;
import org.moera.importer.model.Message;
import org.moera.importer.moera.MoeraOutcomeUnknownException;
import org.moera.importer.moera.MoeraPermanentException;
import org.moera.importer.moera.MoeraService;
import org.moera.importer.moera.MoeraTemporaryException;
import org.moera.importer.observability.ImportMetrics;
import org.moera.importer.persistence.PublicationClaim;
import org.moera.importer.persistence.PublicationContext;
import org.moera.importer.persistence.PublicationDestinationState;
import org.moera.importer.persistence.PublicationRepublishRepository;
import org.moera.importer.persistence.PublicationRepository;
import org.moera.importer.persistence.RepublishState;
import org.moera.lib.node.types.StoryAttributes;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PublicationRepublishProcessorTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final String FIRST_URI = "https://t.me/forwarding/10";
    private static final String SECOND_URI = "https://t.me/forwarding/11";
    private static final Map<String, String> STORIES = Map.of("timeline", "story-1", "news", "story-2");

    private final Destination destination = new Destination(7, "forwarding_0", NOW, NOW);
    private final PublicationClaim claim = new PublicationClaim(17, 5, UUID.randomUUID(), 1);
    private PublicationProcessor publicationProcessor;
    private PublicationRepository publicationRepository;
    private PublicationRepublishRepository publicationRepublishRepository;
    private MoeraService moeraService;
    private ImportMetrics importMetrics;
    private PublicationPayloadBuilder publicationPayloadBuilder;

    @BeforeEach
    void setUp() {
        publicationRepository = mock(PublicationRepository.class);
        publicationRepublishRepository = mock(PublicationRepublishRepository.class);
        moeraService = mock(MoeraService.class);
        importMetrics = mock(ImportMetrics.class);
        publicationPayloadBuilder = mock(PublicationPayloadBuilder.class);
        var republishProcessor = new PublicationRepublishProcessor();
        ReflectionTestUtils.setField(republishProcessor, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(republishProcessor, "moeraService", moeraService);
        ReflectionTestUtils.setField(republishProcessor, "importMetrics", importMetrics);
        ReflectionTestUtils.setField(republishProcessor, "publicationRepository", publicationRepository);
        ReflectionTestUtils.setField(republishProcessor, "publicationRepublishRepository", publicationRepublishRepository);
        publicationProcessor = new PublicationProcessor();
        ReflectionTestUtils.setField(publicationProcessor, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(publicationProcessor, "moeraService", moeraService);
        ReflectionTestUtils.setField(publicationProcessor, "importMetrics", importMetrics);
        ReflectionTestUtils.setField(publicationProcessor, "publicationRepository", publicationRepository);
        ReflectionTestUtils.setField(publicationProcessor, "publicationPayloadBuilder", publicationPayloadBuilder);
        ReflectionTestUtils.setField(publicationProcessor, "publicationRepublishProcessor", republishProcessor);
        ReflectionTestUtils.setField(publicationProcessor, "retryPolicy",
            new RetryPolicy(Duration.ofMinutes(1), Duration.ofHours(1), 0));
        when(publicationRepublishRepository.recordRequest(anyLong(), any(), any(), anyList())).thenReturn(true);
        when(publicationRepublishRepository.saveStories(anyLong(), any(), any())).thenReturn(true);
        when(publicationRepublishRepository.clearRequest(anyLong(), any())).thenReturn(true);
        when(publicationRepublishRepository.recordDeletion(anyLong(), any())).thenReturn(true);
        when(publicationRepublishRepository.awaitStories(anyLong(), any(), any())).thenReturn(true);
        when(publicationRepository.updated(anyLong(), any(), anyInt(), any())).thenReturn(true);
        when(publicationRepository.deleted(anyLong(), any(), anyInt(), any())).thenReturn(true);
    }

    @Test
    void persistsIntentBeforeRepublishingAndSchedulesDiscoveryWithoutCreatingAPost() {
        useContext(null, Map.of(), false);

        publicationProcessor.process(claim);

        var publications = ArgumentCaptor.forClass(List.class);
        var order = inOrder(publicationRepublishRepository, moeraService);
        order.verify(publicationRepublishRepository).recordRequest(17, claim.processingToken(), NOW,
            List.of(FIRST_URI, SECOND_URI));
        order.verify(moeraService).republish(eq(destination), eq("original_0"), eq("original-post"),
            publications.capture());
        @SuppressWarnings("unchecked")
        List<StoryAttributes> stories = publications.getValue();
        assertThat(stories).extracting(StoryAttributes::getFeedName).containsExactly("timeline", "news");
        assertThat(stories).allSatisfy(story -> {
            assertThat(story.getExternalSourceUri()).containsExactly(FIRST_URI, SECOND_URI);
            assertThat(story.getPublishAt()).isEqualTo(NOW.minusSeconds(10).getEpochSecond());
            assertThat(story.getPinned()).isTrue();
        });
        verify(publicationRepublishRepository).awaitStories(17, claim.processingToken(), NOW.plusSeconds(15));
        verifyNoInteractions(publicationPayloadBuilder);
        verify(moeraService, never()).capabilities(any());
        verify(moeraService, never()).createPost(any(), any());
        verify(publicationRepository, never()).updated(anyLong(), any(), anyInt(), any());
    }

    @Test
    void resumesDiscoveryAfterRestartWithoutSendingAnotherRepublish() {
        useContext(NOW.minusSeconds(60), Map.of(), false);

        publicationProcessor.process(claim);

        verify(moeraService, never()).republish(any(), anyString(), anyString(), anyList());
        verify(moeraService).findRepublishedStories(destination, "original_0", "original-post",
            List.of(FIRST_URI), List.of("timeline", "news"));
        verify(publicationRepublishRepository).awaitStories(17, claim.processingToken(), NOW.plusSeconds(15));
    }

    @Test
    void persistsAllDiscoveredStoryIdsThenAppliesCurrentForwardingUrls() {
        useContext(NOW.minusSeconds(60), Map.of(), false);
        found(STORIES);

        publicationProcessor.process(claim);

        var order = inOrder(publicationRepublishRepository, moeraService, publicationRepository);
        order.verify(moeraService).findRepublishedStories(any(), anyString(), anyString(), anyList(), anyList());
        order.verify(publicationRepublishRepository).saveStories(17, claim.processingToken(), STORIES);
        order.verify(publicationRepublishRepository).clearRequest(17, claim.processingToken());
        verify(moeraService).updateStorySources(destination, "story-1", List.of(FIRST_URI, SECOND_URI));
        verify(moeraService).updateStorySources(destination, "story-2", List.of(FIRST_URI, SECOND_URI));
        verify(publicationRepository).updated(17, claim.processingToken(), 5,
            new PublicationDestinationState(true, STORIES));
        verify(moeraService, never()).updatePost(any(), anyString(), any());
    }

    @Test
    void retainsPartialFeedDiscoveryAndWaitsForTheRest() {
        useContext(NOW.minusSeconds(60), Map.of(), false);
        found(Map.of("timeline", "story-1"));

        publicationProcessor.process(claim);

        verify(publicationRepublishRepository).saveStories(17, claim.processingToken(), Map.of("timeline", "story-1"));
        verify(publicationRepublishRepository).awaitStories(17, claim.processingToken(), NOW.plusSeconds(15));
        verify(publicationRepository, never()).updated(anyLong(), any(), anyInt(), any());
        verify(moeraService).updateStorySources(destination, "story-1", List.of(FIRST_URI, SECOND_URI));
    }

    @Test
    void mergesPreviouslyFoundAndNewFeedStories() {
        useContext(NOW.minusSeconds(60), Map.of("timeline", "story-1"), false);
        found(Map.of("news", "story-2"));

        publicationProcessor.process(claim);

        verify(publicationRepository).updated(17, claim.processingToken(), 5,
            new PublicationDestinationState(true, STORIES));
    }

    @Test
    void failsPermanentlyAtTheThirtyMinuteDeadline() {
        useContext(NOW.minus(Duration.ofMinutes(30)), Map.of(), false);

        publicationProcessor.process(claim);

        verify(publicationRepository).fail(eq(17L), eq(claim.processingToken()), eq(5),
            eq("Republished Moera stories were not found within 30 minutes"), isNull());
        verifyNoInteractions(moeraService);
    }

    @Test
    void boundsTheLastPollingDelayByTheDeadline() {
        useContext(NOW.minus(Duration.ofMinutes(30)).plusSeconds(5), Map.of(), false);

        publicationProcessor.process(claim);

        verify(publicationRepublishRepository).awaitStories(17, claim.processingToken(), NOW.plusSeconds(5));
    }

    @Test
    void waitsForDiscoveryAfterUnknownRepublishInsteadOfRepeatingTheRequest() {
        useContext(null, Map.of(), false);
        doThrow(new MoeraOutcomeUnknownException("Republish outcome unknown", null)).when(moeraService)
            .republish(any(), anyString(), anyString(), anyList());

        publicationProcessor.process(claim);

        verify(publicationRepublishRepository).awaitStories(17, claim.processingToken(), NOW.plusSeconds(15));
        verify(publicationRepublishRepository, never()).clearRequest(anyLong(), any());
        verify(importMetrics).publicationUnknownOutcome();
        verify(publicationRepository, never()).retry(anyLong(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void clearsIntentForAKnownRejectedTemporaryRequestBeforeRetrying() {
        useContext(null, Map.of(), false);
        doThrow(new MoeraTemporaryException("Request rejected", null)).when(moeraService)
            .republish(any(), anyString(), anyString(), anyList());

        publicationProcessor.process(claim);

        verify(publicationRepublishRepository).clearRequest(17, claim.processingToken());
        verify(publicationRepository).retry(eq(17L), eq(claim.processingToken()), eq(5), eq(NOW.plusSeconds(60)),
            eq("Request rejected"), any());
    }

    @Test
    void clearsIntentForAKnownRejectedPermanentRequestSoNewInputsCanRearmDelivery() {
        useContext(null, Map.of(), false);
        doThrow(new MoeraPermanentException("Request forbidden", null)).when(moeraService)
            .republish(any(), anyString(), anyString(), anyList());

        publicationProcessor.process(claim);

        verify(publicationRepublishRepository).clearRequest(17, claim.processingToken());
        verify(publicationRepository).fail(17, claim.processingToken(), 5, "Request forbidden", null);
    }

    @Test
    void staleIntentTokenPreventsSendingTheRepublish() {
        useContext(null, Map.of(), false);
        when(publicationRepublishRepository.recordRequest(anyLong(), any(), any(), anyList())).thenReturn(false);

        publicationProcessor.process(claim);

        verifyNoInteractions(moeraService);
    }

    @Test
    void staleStoryPersistencePreventsUpdatingStoriesOrCompletingRevision() {
        useContext(NOW.minusSeconds(60), Map.of(), false);
        found(STORIES);
        when(publicationRepublishRepository.saveStories(anyLong(), any(), any())).thenReturn(false);

        publicationProcessor.process(claim);

        verify(moeraService, never()).updateStorySources(any(), anyString(), anyList());
        verify(publicationRepository, never()).updated(anyLong(), any(), anyInt(), any());
    }

    @Test
    void addsLaterForwardUrlsToExistingStoriesWithoutRepublishing() {
        useContext(null, STORIES, false);

        publicationProcessor.process(claim);

        verify(moeraService).updateStorySources(destination, "story-1", List.of(FIRST_URI, SECOND_URI));
        verify(moeraService, never()).republish(any(), anyString(), anyString(), anyList());
        verify(moeraService, never()).findRepublishedStories(any(), anyString(), anyString(), anyList(), anyList());
    }

    @Test
    void removesDeletedMemberUrlWithoutDeletingRemainingStories() {
        PublicationContext context = context(null, STORIES, false);
        useContext(new PublicationContext(17, 5, destination, context.mirrorFeeds(), null, true, STORIES,
            List.of(member(10, FIRST_URI, true), member(11, SECOND_URI, false)), List.of(), context.republish()));

        publicationProcessor.process(claim);

        verify(moeraService).updateStorySources(destination, "story-1", List.of(SECOND_URI));
        verify(moeraService, never()).deleteStory(any(), anyString());
    }

    @Test
    void deletesOnlyStoriesWhenTheLastForwardingUrlDisappears() {
        useContext(null, STORIES, true);

        publicationProcessor.process(claim);

        verify(moeraService).deleteStory(destination, "story-1");
        verify(moeraService).deleteStory(destination, "story-2");
        verify(moeraService, never()).deletePost(any(), anyString());
        verify(publicationRepository).deleted(17, claim.processingToken(), 5,
            new PublicationDestinationState(null, Map.of()));
    }

    @Test
    void discoversStoriesBySavedUrlsEvenIfAllSourceMessagesWereDeletedWhileWaiting() {
        useContext(NOW.minusSeconds(60), Map.of(), true);
        found(STORIES);

        publicationProcessor.process(claim);

        verify(moeraService).findRepublishedStories(destination, "original_0", "original-post",
            List.of(FIRST_URI), List.of("timeline", "news"));
        verify(moeraService).deleteStory(destination, "story-1");
        verify(moeraService).deleteStory(destination, "story-2");
        verify(moeraService, never()).republish(any(), anyString(), anyString(), anyList());
    }

    @Test
    void deletionIntentPreventsRepublishUntilCleanupCompletesEvenWithNoSavedStoryIds() {
        PublicationContext context = context(null, Map.of(), false);
        useContext(new PublicationContext(17, 5, destination, context.mirrorFeeds(), null, true, Map.of(),
            context.members(), List.of(), new RepublishState("original_0", "original-post", null, List.of(), true)));
        when(publicationRepublishRepository.restartAfterDeletion(17, claim.processingToken())).thenReturn(true);

        publicationProcessor.process(claim);

        verify(moeraService, never()).republish(any(), anyString(), anyString(), anyList());
        verify(publicationRepublishRepository).restartAfterDeletion(17, claim.processingToken());
    }

    @Test
    void completesDeletedNeverRequestedPublicationWithoutExternalCalls() {
        useContext(null, Map.of(), true);

        publicationProcessor.process(claim);

        verifyNoInteractions(moeraService);
        verify(publicationRepository).deleted(17, claim.processingToken(), 5,
            new PublicationDestinationState(null, Map.of()));
    }

    private void found(Map<String, String> stories) {
        when(moeraService.findRepublishedStories(any(), anyString(), anyString(), anyList(), anyList()))
            .thenReturn(stories);
    }

    private void useContext(Instant requestedAt, Map<String, String> stories, boolean deleted) {
        useContext(context(requestedAt, stories, deleted));
    }

    private void useContext(PublicationContext context) {
        when(publicationRepository.loadContext(17, 5)).thenReturn(context);
    }

    private PublicationContext context(Instant requestedAt, Map<String, String> stories, boolean deleted) {
        return new PublicationContext(17, 5, destination, List.of("timeline", "news"), null, true, stories,
            List.of(member(10, FIRST_URI, deleted), member(11, SECOND_URI, deleted)), List.of(),
            new RepublishState("original_0", "original-post", requestedAt,
                requestedAt != null ? List.of(FIRST_URI) : List.of()));
    }

    private PublicationContext.Member member(long id, String uri, boolean deleted) {
        return new PublicationContext.Member(new Message(id, 3, Long.toString(id), 0, deleted, true,
            NOW.minusSeconds(20 - id), "Forwarded", BodyFormat.HTML, new byte[32], uri, null,
            JsonNodeFactory.instance.objectNode(), NOW, NOW), List.of());
    }

}
