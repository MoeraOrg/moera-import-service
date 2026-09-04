package org.moera.importer.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moera.importer.MoeraImportApplication;
import org.moera.importer.model.BodyFormat;
import org.moera.importer.model.SourceType;
import org.moera.importer.persistence.MessageRepository;
import org.moera.importer.persistence.MirrorRepository;
import org.moera.importer.persistence.PostgresIntegrationTest;
import org.moera.importer.source.AccessResult;
import org.moera.importer.source.ChannelResolution;
import org.moera.importer.source.ChannelSnapshot;
import org.moera.importer.source.GroupingRelation;
import org.moera.importer.source.InitialImportMode;
import org.moera.importer.source.PollResult;
import org.moera.importer.source.SourceAdapter;
import org.moera.importer.source.SourceEventSink;
import org.moera.importer.source.SourceLinkPreview;
import org.moera.importer.source.SourceMedia;
import org.moera.importer.source.SourceMessage;
import org.moera.importer.source.SubscriptionResult;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.moera.importer.persistence.jooq.enums.AccessState.GRANTED;
import static org.moera.importer.persistence.jooq.enums.PublicationMediaStatus.PENDING;
import static org.moera.importer.persistence.jooq.enums.PublicationMediaStatus.UPLOADED;
import static org.moera.importer.persistence.jooq.enums.SourceType.TELEGRAM;
import static org.moera.importer.persistence.jooq.enums.SubscriptionState.ACTIVE;
import static org.moera.importer.persistence.jooq.tables.Channels.CHANNELS;
import static org.moera.importer.persistence.jooq.tables.Destinations.DESTINATIONS;
import static org.moera.importer.persistence.jooq.tables.Mirrors.MIRRORS;
import static org.moera.importer.persistence.jooq.tables.Media.MEDIA;
import static org.moera.importer.persistence.jooq.tables.PublicationMedia.PUBLICATION_MEDIA;
import static org.moera.importer.persistence.jooq.tables.PublicationMessages.PUBLICATION_MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Publications.PUBLICATIONS;

@SpringBootTest(classes = { MoeraImportApplication.class, PublicationBuilderTest.Configuration.class })
class PublicationBuilderTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");

    @Inject
    private DSLContext dslContext;

    @Inject
    private MessageRepository messageRepository;

    @Inject
    private MirrorRepository mirrorRepository;

    @Inject
    private PublicationBuilder publicationBuilder;

    @Inject
    private PlatformTransactionManager platformTransactionManager;

    @BeforeEach
    void clearDatabase() {
        dslContext.truncate(CHANNELS).restartIdentity().cascade().execute();
        dslContext.truncate(DESTINATIONS).restartIdentity().cascade().execute();
    }

    @Test
    void buildsOnePublicationForConsecutiveGapsWithinThreshold() {
        long mirrorId = insertMirror();
        ingest("one", NOW.minusSeconds(100));
        ingest("two", NOW.minusSeconds(90));
        ingest("three", NOW.minusSeconds(70));

        publicationBuilder.build(mirrorId);

        assertPublicationGroups(List.of(List.of("one", "two", "three")));
    }

    @Test
    void splitsPublicationsAtGapBeyondThreshold() {
        long mirrorId = insertMirror();
        ingest("one", NOW.minusSeconds(100));
        ingest("two", NOW.minusSeconds(90));
        ingest("three", NOW.minusSeconds(50));

        publicationBuilder.build(mirrorId);

        assertPublicationGroups(List.of(List.of("one", "two"), List.of("three")));
    }

    @Test
    void mustGroupJoinsMessagesBeyondThreshold() {
        long mirrorId = insertMirror();
        ingest("force-group-1", NOW.minusSeconds(100));
        ingest("force-group-2", NOW.minusSeconds(50));

        publicationBuilder.build(mirrorId);

        assertPublicationGroups(List.of(List.of("force-group-1", "force-group-2")));
    }

    @Test
    void mustSeparateSplitsMessagesInsideThreshold() {
        long mirrorId = insertMirror();
        ingest("force-separate-1", NOW.minusSeconds(100));
        ingest("force-separate-2", NOW.minusSeconds(90));

        publicationBuilder.build(mirrorId);

        assertPublicationGroups(List.of(List.of("force-separate-1"), List.of("force-separate-2")));
    }

    @Test
    void leavesFinalLiveGroupUnpublishedUntilItSettles() {
        long mirrorId = insertMirror();
        ingest("one", NOW.minusSeconds(15));
        ingest("two", NOW.minusSeconds(5));

        publicationBuilder.build(mirrorId);

        assertThat(dslContext.fetchCount(PUBLICATIONS)).isZero();
    }

    @Test
    void createsHistoricalFinalGroupAfterItSettles() {
        long mirrorId = insertMirror();
        ingest("one", NOW.minusSeconds(50));
        ingest("two", NOW.minusSeconds(40));

        publicationBuilder.build(mirrorId);

        assertPublicationGroups(List.of(List.of("one", "two")));
        assertThat(dslContext.select(PUBLICATIONS.REVISION).from(PUBLICATIONS).fetchSingle(PUBLICATIONS.REVISION))
            .isZero();
        assertThat(dslContext.select(PUBLICATIONS.PUBLISHED_REVISION).from(PUBLICATIONS)
            .fetchSingle(PUBLICATIONS.PUBLISHED_REVISION)).isNull();
        assertThat(dslContext.select(PUBLICATIONS.NEXT_ATTEMPT_AT).from(PUBLICATIONS)
            .fetchSingle(PUBLICATIONS.NEXT_ATTEMPT_AT).toInstant()).isEqualTo(NOW);
    }

    @Test
    void createsPendingDeliveryRowsForCurrentMedia() {
        long mirrorId = insertMirror();
        ingest(
            "one",
            NOW.minusSeconds(50),
            List.of(new SourceMedia(
                "media-1",
                0,
                "image/jpeg",
                "photo.jpg",
                100L,
                hash(2),
                JsonNodeFactory.instance.objectNode()
            ))
        );

        publicationBuilder.build(mirrorId);

        var publicationMedia = dslContext.selectFrom(PUBLICATION_MEDIA).fetchSingle();
        assertThat(publicationMedia.getStatus())
            .isEqualTo(org.moera.importer.persistence.jooq.enums.PublicationMediaStatus.PENDING);
        assertThat(publicationMedia.getNextAttemptAt().toInstant()).isEqualTo(NOW);
    }

    @Test
    void keepsOnlyTheFirstPreviewImageForEachUrlAsEffectiveMedia() {
        long mirrorId = insertMirror();
        ingest(
            "one",
            NOW.minusSeconds(50),
            List.of(media("ordinary-one", 0), media("preview-one", 1)),
            List.of(preview("https://example.com/shared", "preview-one"))
        );
        ingest(
            "two",
            NOW.minusSeconds(40),
            List.of(media("ordinary-two", 0), media("preview-two", 1)),
            List.of(preview("https://example.com/shared", "preview-two"))
        );

        publicationBuilder.build(mirrorId);

        assertThat(effectiveMediaSourceIds())
            .containsExactlyInAnyOrder("ordinary-one", "preview-one", "ordinary-two");
    }

    @Test
    void activatesLaterPreviewImageWhenTheEarlierPreviewIsRemoved() {
        long mirrorId = insertMirror();
        ingest(
            "one",
            NOW.minusSeconds(50),
            List.of(media("ordinary-one", 0), media("preview-one", 1)),
            List.of(preview("https://example.com/shared", "preview-one"))
        );
        ingest(
            "two",
            NOW.minusSeconds(40),
            List.of(media("ordinary-two", 0), media("preview-two", 1)),
            List.of(preview("https://example.com/shared", "preview-two"))
        );
        publicationBuilder.build(mirrorId);
        dslContext.update(PUBLICATION_MEDIA)
            .set(PUBLICATION_MEDIA.STATUS, UPLOADED)
            .set(PUBLICATION_MEDIA.DESTINATION_MEDIA_ID, "uploaded-media")
            .setNull(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT)
            .execute();

        ingest("one", NOW.minusSeconds(50), List.of(media("ordinary-one", 0)), List.of());

        assertThat(effectiveMediaSourceIds())
            .containsExactlyInAnyOrder("ordinary-one", "ordinary-two", "preview-two");
        var ordinaryDelivery = dslContext.select(PUBLICATION_MEDIA.STATUS, PUBLICATION_MEDIA.DESTINATION_MEDIA_ID)
            .from(PUBLICATION_MEDIA)
            .join(MEDIA).on(PUBLICATION_MEDIA.MEDIA_ID.eq(MEDIA.ID))
            .where(MEDIA.SOURCE_ID.eq("ordinary-one"))
            .fetchSingle();
        assertThat(ordinaryDelivery.value1()).isEqualTo(UPLOADED);
        assertThat(ordinaryDelivery.value2()).isEqualTo("uploaded-media");
        var activatedPreview = dslContext.select(PUBLICATION_MEDIA.STATUS, PUBLICATION_MEDIA.NEXT_ATTEMPT_AT)
            .from(PUBLICATION_MEDIA)
            .join(MEDIA).on(PUBLICATION_MEDIA.MEDIA_ID.eq(MEDIA.ID))
            .where(MEDIA.SOURCE_ID.eq("preview-two"))
            .fetchSingle();
        assertThat(activatedPreview.value1()).isEqualTo(PENDING);
        assertThat(activatedPreview.value2().toInstant()).isEqualTo(NOW);
    }

    @Test
    void keepsExistingMembershipImmutableWhenLateMessageArrives() {
        long mirrorId = insertMirror();
        ingest("one", NOW.minusSeconds(50));
        publicationBuilder.build(mirrorId);

        ingest("late", NOW.minusSeconds(40));
        publicationBuilder.build(mirrorId);

        assertPublicationGroups(List.of(List.of("one"), List.of("late")));
    }

    @Test
    void findsOnlyEnabledMirrorsThatStillHaveUnassignedMessages() {
        long mirrorId = insertMirror();
        ingest("one", NOW.minusSeconds(50));

        assertThat(mirrorRepository.findWithUnassignedMessages(10)).containsExactly(mirrorId);

        dslContext.update(MIRRORS).set(MIRRORS.ENABLED, false).where(MIRRORS.ID.eq(mirrorId)).execute();
        assertThat(mirrorRepository.findWithUnassignedMessages(10)).isEmpty();

        dslContext.update(MIRRORS).set(MIRRORS.ENABLED, true).where(MIRRORS.ID.eq(mirrorId)).execute();
        publicationBuilder.build(mirrorId);
        assertThat(mirrorRepository.findWithUnassignedMessages(10)).isEmpty();
    }

    @Test
    void returnsWithoutBuildingWhenMirrorRowIsAlreadyLocked() throws Exception {
        long mirrorId = insertMirror();
        ingest("one", NOW.minusSeconds(50));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var lockHolder = executor.submit(() -> new TransactionTemplate(platformTransactionManager)
                .executeWithoutResult(status -> {
                    dslContext.select(MIRRORS.ID)
                        .from(MIRRORS)
                        .where(MIRRORS.ID.eq(mirrorId))
                        .forUpdate()
                        .fetchSingle();
                    locked.countDown();
                    await(release);
                }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            publicationBuilder.build(mirrorId);

            assertThat(dslContext.fetchCount(PUBLICATIONS)).isZero();
            release.countDown();
            lockHolder.get();
        } finally {
            release.countDown();
        }

        publicationBuilder.build(mirrorId);
        assertPublicationGroups(List.of(List.of("one")));
    }

    private long insertMirror() {
        long channelId = dslContext.insertInto(CHANNELS)
            .set(CHANNELS.SOURCE_TYPE, TELEGRAM)
            .set(CHANNELS.SOURCE_ID, "channel-1")
            .set(CHANNELS.SOURCE_URI, "https://t.me/channel-1")
            .set(CHANNELS.ACCESS_STATE, GRANTED)
            .set(CHANNELS.SUBSCRIPTION_STATE, ACTIVE)
            .returningResult(CHANNELS.ID)
            .fetchSingle(CHANNELS.ID);
        long destinationId = dslContext.insertInto(DESTINATIONS)
            .set(DESTINATIONS.NODE_NAME, "node.moera.org")
            .returningResult(DESTINATIONS.ID)
            .fetchSingle(DESTINATIONS.ID);
        return dslContext.insertInto(MIRRORS)
            .set(MIRRORS.CHANNEL_ID, channelId)
            .set(MIRRORS.DESTINATION_ID, destinationId)
            .set(MIRRORS.FEEDS, new String[] { "timeline" })
            .returningResult(MIRRORS.ID)
            .fetchSingle(MIRRORS.ID);
    }

    private void ingest(String sourceId, Instant publishedAt) {
        ingest(sourceId, publishedAt, List.of());
    }

    private void ingest(String sourceId, Instant publishedAt, List<SourceMedia> media) {
        ingest(sourceId, publishedAt, media, List.of());
    }

    private void ingest(
        String sourceId,
        Instant publishedAt,
        List<SourceMedia> media,
        List<SourceLinkPreview> linkPreviews
    ) {
        messageRepository.ingest(1, new SourceMessage(
            sourceId,
            false,
            false,
            publishedAt,
            sourceId,
            BodyFormat.PLAIN_TEXT,
            hash(sourceId.hashCode()),
            JsonNodeFactory.instance.objectNode(),
            media,
            linkPreviews
        ));
    }

    private SourceMedia media(String sourceId, int position) {
        return new SourceMedia(
            sourceId,
            position,
            "image/jpeg",
            sourceId + ".jpg",
            100L,
            hash(sourceId.hashCode()),
            JsonNodeFactory.instance.objectNode()
        );
    }

    private SourceLinkPreview preview(String url, String imageSourceMediaId) {
        return new SourceLinkPreview(null, url, null, null, null, imageSourceMediaId);
    }

    private List<String> effectiveMediaSourceIds() {
        return dslContext.select(MEDIA.SOURCE_ID)
            .from(PUBLICATION_MEDIA)
            .join(MEDIA).on(PUBLICATION_MEDIA.MEDIA_ID.eq(MEDIA.ID))
            .fetch(MEDIA.SOURCE_ID);
    }

    private void assertPublicationGroups(List<List<String>> expectedGroups) {
        List<Long> publicationIds = dslContext.select(PUBLICATIONS.ID)
            .from(PUBLICATIONS)
            .orderBy(PUBLICATIONS.ID)
            .fetch(PUBLICATIONS.ID);
        assertThat(publicationIds).hasSameSizeAs(expectedGroups);
        for (int index = 0; index < publicationIds.size(); index++) {
            assertThat(dslContext.select(org.moera.importer.persistence.jooq.tables.Messages.MESSAGES.SOURCE_ID)
                .from(PUBLICATION_MESSAGES)
                .join(org.moera.importer.persistence.jooq.tables.Messages.MESSAGES)
                .on(PUBLICATION_MESSAGES.MESSAGE_ID.eq(
                    org.moera.importer.persistence.jooq.tables.Messages.MESSAGES.ID))
                .where(PUBLICATION_MESSAGES.PUBLICATION_ID.eq(publicationIds.get(index)))
                .orderBy(PUBLICATION_MESSAGES.POSITION)
                .fetch(org.moera.importer.persistence.jooq.tables.Messages.MESSAGES.SOURCE_ID))
                .containsExactlyElementsOf(expectedGroups.get(index));
        }
    }

    private byte[] hash(int value) {
        byte[] hash = new byte[32];
        hash[0] = (byte) value;
        return hash;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for publication-builder test coordination");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating publication-builder test", e);
        }
    }

    @TestConfiguration
    static class Configuration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        SourceAdapter sourceAdapter() {
            return new TestSourceAdapter();
        }

    }

    private static class TestSourceAdapter implements SourceAdapter {

        @Override
        public SourceType type() {
            return SourceType.TELEGRAM;
        }

        @Override
        public ChannelResolution resolveChannel(java.net.URI sourceUri, InitialImportMode initialImportMode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Duration groupingThreshold() {
            return Duration.ofSeconds(30);
        }

        @Override
        public Duration settlingDelay() {
            return Duration.ofSeconds(30);
        }

        @Override
        public Duration pollingInterval(ChannelSnapshot channel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GroupingRelation groupingRelation(SourceMessage previous, SourceMessage next) {
            if (previous.sourceId().equals("force-group-1") && next.sourceId().equals("force-group-2")) {
                return GroupingRelation.MUST_GROUP;
            }
            if (previous.sourceId().equals("force-separate-1") && next.sourceId().equals("force-separate-2")) {
                return GroupingRelation.MUST_SEPARATE;
            }
            return GroupingRelation.UNKNOWN;
        }

        @Override
        public AccessResult refreshAccess(ChannelSnapshot channel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SubscriptionResult refreshSubscription(ChannelSnapshot channel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PollResult poll(ChannelSnapshot channel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void downloadMedia(ChannelSnapshot channel, org.moera.importer.source.MediaSnapshot media,
                                  java.nio.file.Path destination) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void start(SourceEventSink sink) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void stop() {
            throw new UnsupportedOperationException();
        }

    }

}
