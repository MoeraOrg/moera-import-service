package org.moera.importer.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moera.importer.model.BodyFormat;
import org.moera.importer.model.Message;
import org.moera.importer.source.SourceLinkPreview;
import org.moera.importer.source.SourceMedia;
import org.moera.importer.source.SourceMessage;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.moera.importer.persistence.jooq.enums.AccessState.GRANTED;
import static org.moera.importer.persistence.jooq.enums.PublicationMediaStatus.PENDING;
import static org.moera.importer.persistence.jooq.enums.PublicationMediaStatus.UPLOADED;
import static org.moera.importer.persistence.jooq.enums.SourceType.TELEGRAM;
import static org.moera.importer.persistence.jooq.enums.SubscriptionState.ACTIVE;
import static org.moera.importer.persistence.jooq.tables.Channels.CHANNELS;
import static org.moera.importer.persistence.jooq.tables.Destinations.DESTINATIONS;
import static org.moera.importer.persistence.jooq.tables.Media.MEDIA;
import static org.moera.importer.persistence.jooq.tables.Messages.MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Mirrors.MIRRORS;
import static org.moera.importer.persistence.jooq.tables.PublicationMedia.PUBLICATION_MEDIA;
import static org.moera.importer.persistence.jooq.tables.Publications.PUBLICATIONS;

@SpringBootTest
class PublicationRevisionRaceTest extends PostgresIntegrationTest {

    @Inject
    private DSLContext dslContext;

    @Inject
    private JooqMappings jooqMappings;

    @Inject
    private MessageRepository messageRepository;

    @Inject
    private PublicationRepository publicationRepository;

    @BeforeEach
    void clearDatabase() {
        dslContext.truncate(CHANNELS).restartIdentity().cascade().execute();
        dslContext.truncate(DESTINATIONS).restartIdentity().cascade().execute();
    }

    @Test
    void claimsOnlyDueDirtyPublicationWhoseMediaIsTerminalAndLeaseIsAvailable() {
        Fixture fixture = insertFixture();
        OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        OffsetDateTime future = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5);
        insertPublication(fixture.mirrorId(), 2, 2, past, null, null);
        insertPublication(fixture.mirrorId(), 2, 1, future, null, null);
        long pending = insertPublication(fixture.mirrorId(), 2, 1, past, null, null);
        insertPublicationMedia(pending, fixture.mediaId(), PENDING, null, false);
        long compression = insertPublication(fixture.mirrorId(), 2, 1, past, null, null);
        insertPublicationMedia(compression, fixture.mediaId(), PENDING, "original-id", true);
        long leased = insertPublication(
            fixture.mirrorId(), 2, 1, past, UUID.randomUUID(), future
        );
        long ready = insertPublication(fixture.mirrorId(), 3, 2, past, null, null);
        insertPublicationMedia(ready, fixture.mediaId(), UPLOADED, "media-id", false);

        List<PublicationClaim> claims = publicationRepository.claimReady(10, Duration.ofMinutes(5));

        assertThat(claims).singleElement().satisfies(claim -> {
            assertThat(claim.publicationId()).isEqualTo(ready);
            assertThat(claim.targetRevision()).isEqualTo(3);
            assertThat(claim.attempts()).isEqualTo(1);
            assertThat(claim.processingToken()).isNotNull();
        });
        assertThat(dslContext.select(PUBLICATIONS.PROCESSING_TOKEN)
            .from(PUBLICATIONS)
            .where(PUBLICATIONS.ID.eq(leased))
            .fetchSingle(PUBLICATIONS.PROCESSING_TOKEN)).isNotNull();
        assertThat(publicationRepository.claimReady(10, Duration.ofMinutes(5))).isEmpty();
    }

    @Test
    void reclaimsExpiredLeaseWithNewTokenAndIncrementedAttempt() {
        Fixture fixture = insertFixture();
        UUID expiredToken = UUID.randomUUID();
        long publicationId = insertPublication(
            fixture.mirrorId(),
            4,
            3,
            OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2),
            expiredToken,
            OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1)
        );
        dslContext.update(PUBLICATIONS)
            .set(PUBLICATIONS.ATTEMPTS, 2)
            .where(PUBLICATIONS.ID.eq(publicationId))
            .execute();

        PublicationClaim claim = publicationRepository.claimReady(1, Duration.ofMinutes(5)).getFirst();

        assertThat(claim.publicationId()).isEqualTo(publicationId);
        assertThat(claim.targetRevision()).isEqualTo(4);
        assertThat(claim.attempts()).isEqualTo(3);
        assertThat(claim.processingToken()).isNotEqualTo(expiredToken);
    }

    @Test
    void completesOnlyCapturedRevisionWhenSourceChangesAfterSnapshot() {
        long channelId = insertChannel();
        IngestResult ingestResult = messageRepository.ingest(channelId, sourceMessage("Old body", false, hash(3)));
        long destinationId = insertDestination();
        long mirrorId = insertMirror(channelId, destinationId);
        Message message = jooqMappings.message(dslContext.selectFrom(MESSAGES)
            .where(MESSAGES.ID.eq(ingestResult.messageId()))
            .fetchSingle());
        publicationRepository.create(mirrorId, List.of(message));
        long publicationId = dslContext.select(PUBLICATIONS.ID).from(PUBLICATIONS)
            .fetchSingle(PUBLICATIONS.ID);
        dslContext.update(PUBLICATIONS)
            .set(PUBLICATIONS.REVISION, 5)
            .set(PUBLICATIONS.DESTINATION_POST_ID, "existing-post")
            .set(PUBLICATIONS.DESTINATION_DATA, jooqMappings.json(JsonNodeFactory.instance.objectNode()
                .put("pinned", false)
                .set("storyIds", JsonNodeFactory.instance.objectNode().put("timeline", "story-1"))))
            .set(PUBLICATIONS.NEXT_ATTEMPT_AT, OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1))
            .where(PUBLICATIONS.ID.eq(publicationId))
            .execute();
        dslContext.update(PUBLICATION_MEDIA)
            .set(PUBLICATION_MEDIA.STATUS, UPLOADED)
            .set(PUBLICATION_MEDIA.DESTINATION_MEDIA_ID, "uploaded-id")
            .set(PUBLICATION_MEDIA.DESTINATION_DATA, jooqMappings.json(
                JsonNodeFactory.instance.objectNode().put("hash", "uploaded-hash")
            ))
            .setNull(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT)
            .execute();
        PublicationClaim claim = publicationRepository.claimReady(1, Duration.ofMinutes(5)).getFirst();

        PublicationContext context = publicationRepository.loadContext(publicationId, claim.targetRevision());
        messageRepository.ingest(channelId, sourceMessage("New body", true, hash(4)));
        boolean completed = publicationRepository.updated(
            publicationId,
            claim.processingToken(),
            claim.targetRevision(),
            JsonNodeFactory.instance.objectNode().put("pinned", false)
        );

        assertThat(completed).isTrue();
        assertThat(context.targetRevision()).isEqualTo(5);
        assertThat(context.destinationPostId()).isEqualTo("existing-post");
        assertThat(context.destinationData().path("storyIds").path("timeline").asText()).isEqualTo("story-1");
        assertThat(context.mirrorConfig().path("feeds").get(0).asText()).isEqualTo("timeline");
        assertThat(context.members()).singleElement().satisfies(member -> {
            assertThat(member.message().body()).isEqualTo("Old body");
            assertThat(member.message().pinned()).isFalse();
            assertThat(member.message().sourceData().path("version").asText()).isEqualTo("old");
            assertThat(member.linkPreviews()).singleElement().satisfies(preview -> {
                assertThat(preview.url()).isEqualTo("https://example.com/article");
                assertThat(preview.imageMediaId()).isEqualTo(context.media().getFirst().media().id());
            });
        });
        assertThat(context.media()).singleElement().satisfies(delivered -> {
            assertThat(delivered.destinationMediaId()).isEqualTo("uploaded-id");
            assertThat(delivered.destinationMediaHash()).isEqualTo("uploaded-hash");
        });
        var publication = dslContext.selectFrom(PUBLICATIONS)
            .where(PUBLICATIONS.ID.eq(publicationId))
            .fetchSingle();
        assertThat(publication.getRevision()).isEqualTo(6);
        assertThat(publication.getPublishedRevision()).isEqualTo(5);
        assertThat(publication.getNextAttemptAt()).isNotNull();
        assertThat(publication.getProcessingToken()).isNull();
    }

    @Test
    void rejectsSnapshotWhenClaimedRevisionAlreadyAdvanced() {
        Fixture fixture = insertFixture();
        long publicationId = insertPublication(
            fixture.mirrorId(),
            5,
            4,
            OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1),
            null,
            null
        );
        PublicationClaim claim = publicationRepository.claimReady(1, Duration.ofMinutes(5)).getFirst();
        dslContext.update(PUBLICATIONS)
            .set(PUBLICATIONS.REVISION, 6)
            .where(PUBLICATIONS.ID.eq(publicationId))
            .execute();

        assertThatThrownBy(() -> publicationRepository.loadContext(publicationId, claim.targetRevision()))
            .isInstanceOf(PublicationSnapshotChangedException.class);
    }

    @Test
    void createAndDeleteCompletionsPersistDestinationStateConditionallyByToken() {
        Fixture fixture = insertFixture();
        long publicationId = insertPublication(
            fixture.mirrorId(),
            1,
            0,
            OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1),
            null,
            null
        );
        PublicationClaim createClaim = publicationRepository.claimReady(1, Duration.ofMinutes(5)).getFirst();
        var createdData = JsonNodeFactory.instance.objectNode().put("pinned", true);

        boolean created = publicationRepository.created(
            publicationId,
            createClaim.processingToken(),
            createClaim.targetRevision(),
            "posting-1",
            createdData
        );

        assertThat(created).isTrue();
        var afterCreate = dslContext.selectFrom(PUBLICATIONS)
            .where(PUBLICATIONS.ID.eq(publicationId))
            .fetchSingle();
        assertThat(afterCreate.getDestinationPostId()).isEqualTo("posting-1");
        assertThat(jooqMappings.jsonNode(afterCreate.getDestinationData())).isEqualTo(createdData);
        assertThat(afterCreate.getPublishedRevision()).isEqualTo(1);
        assertThat(afterCreate.getNextAttemptAt()).isNull();
        assertThat(afterCreate.getAttempts()).isZero();

        dslContext.update(PUBLICATIONS)
            .set(PUBLICATIONS.REVISION, 2)
            .set(PUBLICATIONS.NEXT_ATTEMPT_AT, OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1))
            .where(PUBLICATIONS.ID.eq(publicationId))
            .execute();
        PublicationClaim deleteClaim = publicationRepository.claimReady(1, Duration.ofMinutes(5)).getFirst();
        assertThat(publicationRepository.deleted(
            publicationId,
            UUID.randomUUID(),
            deleteClaim.targetRevision(),
            JsonNodeFactory.instance.objectNode()
        )).isFalse();

        boolean deleted = publicationRepository.deleted(
            publicationId,
            deleteClaim.processingToken(),
            deleteClaim.targetRevision(),
            JsonNodeFactory.instance.objectNode()
        );

        assertThat(deleted).isTrue();
        var afterDelete = dslContext.selectFrom(PUBLICATIONS)
            .where(PUBLICATIONS.ID.eq(publicationId))
            .fetchSingle();
        assertThat(afterDelete.getDestinationPostId()).isNull();
        assertThat(jooqMappings.jsonNode(afterDelete.getDestinationData()))
            .isEqualTo(JsonNodeFactory.instance.objectNode());
        assertThat(afterDelete.getPublishedRevision()).isEqualTo(2);
        assertThat(afterDelete.getNextAttemptAt()).isNull();
    }

    private Fixture insertFixture() {
        long channelId = insertChannel();
        long messageId = dslContext.insertInto(MESSAGES)
            .set(MESSAGES.CHANNEL_ID, channelId)
            .set(MESSAGES.SOURCE_ID, "message-1")
            .set(MESSAGES.PUBLISHED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(MESSAGES.SOURCE_BODY_HASH, hash(1))
            .returningResult(MESSAGES.ID)
            .fetchSingle(MESSAGES.ID);
        long mediaId = dslContext.insertInto(MEDIA)
            .set(MEDIA.MESSAGE_ID, messageId)
            .set(MEDIA.SOURCE_ID, "media-1")
            .set(MEDIA.POSITION, 0)
            .set(MEDIA.SOURCE_HASH, hash(2))
            .returningResult(MEDIA.ID)
            .fetchSingle(MEDIA.ID);
        long destinationId = insertDestination();
        long mirrorId = insertMirror(channelId, destinationId);
        return new Fixture(mirrorId, mediaId);
    }

    private long insertChannel() {
        return dslContext.insertInto(CHANNELS)
            .set(CHANNELS.SOURCE_TYPE, TELEGRAM)
            .set(CHANNELS.SOURCE_ID, "channel-1")
            .set(CHANNELS.SOURCE_URI, "https://t.me/channel-1")
            .set(CHANNELS.ACCESS_STATE, GRANTED)
            .set(CHANNELS.SUBSCRIPTION_STATE, ACTIVE)
            .returningResult(CHANNELS.ID)
            .fetchSingle(CHANNELS.ID);
    }

    private long insertDestination() {
        return dslContext.insertInto(DESTINATIONS)
            .set(DESTINATIONS.NODE_NAME, "destination.example")
            .returningResult(DESTINATIONS.ID)
            .fetchSingle(DESTINATIONS.ID);
    }

    private long insertMirror(long channelId, long destinationId) {
        return dslContext.insertInto(MIRRORS)
            .set(MIRRORS.CHANNEL_ID, channelId)
            .set(MIRRORS.DESTINATION_ID, destinationId)
            .set(MIRRORS.CONFIG, jooqMappings.json(mirrorConfig()))
            .returningResult(MIRRORS.ID)
            .fetchSingle(MIRRORS.ID);
    }

    private com.fasterxml.jackson.databind.JsonNode mirrorConfig() {
        var config = JsonNodeFactory.instance.objectNode();
        config.putArray("feeds").add("timeline");
        return config;
    }

    private SourceMessage sourceMessage(String body, boolean pinned, byte[] bodyHash) {
        return new SourceMessage(
            "message-1",
            false,
            pinned,
            Instant.parse("2026-08-27T12:00:00Z"),
            body,
            BodyFormat.HTML,
            bodyHash,
            JsonNodeFactory.instance.objectNode().put("version", body.startsWith("Old") ? "old" : "new"),
            List.of(new SourceMedia(
                "media-1",
                0,
                "image/jpeg",
                "image.jpg",
                100L,
                hash(2),
                JsonNodeFactory.instance.objectNode()
            )),
            List.of(new SourceLinkPreview(
                "Example",
                "https://example.com/article",
                "Article",
                "Description",
                Instant.parse("2026-08-27T11:00:00Z"),
                "media-1"
            ))
        );
    }

    private long insertPublication(
        long mirrorId,
        int revision,
        Integer publishedRevision,
        OffsetDateTime nextAttemptAt,
        UUID processingToken,
        OffsetDateTime processingUntil
    ) {
        return dslContext.insertInto(PUBLICATIONS)
            .set(PUBLICATIONS.MIRROR_ID, mirrorId)
            .set(PUBLICATIONS.REVISION, revision)
            .set(PUBLICATIONS.PUBLISHED_REVISION, publishedRevision)
            .set(PUBLICATIONS.NEXT_ATTEMPT_AT, nextAttemptAt)
            .set(PUBLICATIONS.PROCESSING_TOKEN, processingToken)
            .set(PUBLICATIONS.PROCESSING_UNTIL, processingUntil)
            .returningResult(PUBLICATIONS.ID)
            .fetchSingle(PUBLICATIONS.ID);
    }

    private void insertPublicationMedia(
        long publicationId,
        long mediaId,
        org.moera.importer.persistence.jooq.enums.PublicationMediaStatus status,
        String destinationMediaId,
        boolean compressionPending
    ) {
        dslContext.insertInto(PUBLICATION_MEDIA)
            .set(PUBLICATION_MEDIA.PUBLICATION_ID, publicationId)
            .set(PUBLICATION_MEDIA.MEDIA_ID, mediaId)
            .set(PUBLICATION_MEDIA.STATUS, status)
            .set(PUBLICATION_MEDIA.DESTINATION_MEDIA_ID, destinationMediaId)
            .set(PUBLICATION_MEDIA.DESTINATION_DATA, jooqMappings.json(
                JsonNodeFactory.instance.objectNode().put("compressionPending", compressionPending)
            ))
            .execute();
    }

    private byte[] hash(int firstByte) {
        byte[] hash = new byte[32];
        hash[0] = (byte) firstByte;
        return hash;
    }

    private record Fixture(long mirrorId, long mediaId) {
    }

}
