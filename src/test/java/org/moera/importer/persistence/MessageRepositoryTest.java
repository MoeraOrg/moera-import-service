package org.moera.importer.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.moera.importer.model.BodyFormat;
import org.moera.importer.source.SourceMedia;
import org.moera.importer.source.SourceMessage;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.moera.importer.persistence.jooq.enums.AccessState.GRANTED;
import static org.moera.importer.persistence.jooq.enums.SourceType.TELEGRAM;
import static org.moera.importer.persistence.jooq.enums.SubscriptionState.ACTIVE;
import static org.moera.importer.persistence.jooq.tables.Channels.CHANNELS;
import static org.moera.importer.persistence.jooq.tables.Media.MEDIA;
import static org.moera.importer.persistence.jooq.tables.Messages.MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Destinations.DESTINATIONS;
import static org.moera.importer.persistence.jooq.tables.Mirrors.MIRRORS;
import static org.moera.importer.persistence.jooq.tables.PublicationMedia.PUBLICATION_MEDIA;
import static org.moera.importer.persistence.jooq.tables.PublicationMessages.PUBLICATION_MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Publications.PUBLICATIONS;
import static org.moera.importer.persistence.jooq.enums.PublicationMediaStatus.UPLOADED;

@SpringBootTest
class MessageRepositoryTest extends PostgresIntegrationTest {

    @Inject
    private DSLContext dslContext;

    @Inject
    private JooqMappings jooqMappings;

    @Inject
    private MessageRepository messageRepository;

    @BeforeEach
    void clearDatabase() {
        dslContext.truncate(CHANNELS).restartIdentity().cascade().execute();
        dslContext.truncate(DESTINATIONS).restartIdentity().cascade().execute();
    }

    @AfterEach
    void removeInsertDelay() {
        dslContext.execute("DROP TRIGGER IF EXISTS delay_message_insert ON messages");
        dslContext.execute("DROP FUNCTION IF EXISTS delay_message_insert()");
    }

    @Test
    void ingestsNewMessageAtRevisionZero() {
        long channelId = insertChannel();
        byte[] bodyHash = hash(1);
        byte[] firstMediaHash = hash(2);
        byte[] secondMediaHash = hash(3);
        SourceMessage sourceMessage = new SourceMessage(
            "message-1",
            false,
            Instant.parse("2026-08-25T10:15:30Z"),
            "Hello, Moera",
            BodyFormat.HTML,
            bodyHash,
            JsonNodeFactory.instance.objectNode().put("source", "message"),
            List.of(
                new SourceMedia(
                    "media-2",
                    1,
                    "video/mp4",
                    "clip.mp4",
                    200L,
                    secondMediaHash,
                    JsonNodeFactory.instance.objectNode().put("remote", "second")
                ),
                new SourceMedia(
                    "media-1",
                    0,
                    "image/jpeg",
                    "photo.jpg",
                    100L,
                    firstMediaHash,
                    JsonNodeFactory.instance.objectNode().put("remote", "first")
                )
            )
        );

        IngestResult result = messageRepository.ingest(channelId, sourceMessage);

        var message = dslContext.selectFrom(MESSAGES).fetchSingle();
        assertThat(result.messageId()).isEqualTo(message.getId());
        assertThat(result.revision()).isZero();
        assertThat(result.created()).isTrue();
        assertThat(result.changed()).isTrue();
        assertThat(message.getRevision()).isZero();
        assertThat(message.getBody()).isEqualTo("Hello, Moera");
        assertThat(message.getBodyFormat().getLiteral()).isEqualTo("HTML");
        assertThat(message.getSourceBodyHash()).containsExactly(bodyHash);

        var media = dslContext.selectFrom(MEDIA).orderBy(MEDIA.POSITION).fetch();
        assertThat(media).hasSize(2);
        assertThat(media.getFirst().getSourceId()).isEqualTo("media-1");
        assertThat(media.getFirst().getPosition()).isZero();
        assertThat(media.getFirst().getSourceHash()).containsExactly(firstMediaHash);
        assertThat(media.getLast().getSourceId()).isEqualTo("media-2");
        assertThat(media.getLast().getPosition()).isEqualTo(1);
        assertThat(media.getLast().getSourceHash()).containsExactly(secondMediaHash);
    }

    @Test
    void identicalReingestDoesNotChangeMessageOrMedia() {
        long channelId = insertChannel();
        SourceMessage sourceMessage = messageWithOneMedia();
        IngestResult firstResult = messageRepository.ingest(channelId, sourceMessage);
        List<Long> mediaIds = dslContext.select(MEDIA.ID).from(MEDIA).fetch(MEDIA.ID);

        IngestResult secondResult = messageRepository.ingest(channelId, sourceMessage);

        assertThat(secondResult.messageId()).isEqualTo(firstResult.messageId());
        assertThat(secondResult.revision()).isZero();
        assertThat(secondResult.created()).isFalse();
        assertThat(secondResult.changed()).isFalse();
        assertThat(dslContext.fetchCount(MESSAGES)).isEqualTo(1);
        assertThat(dslContext.select(MEDIA.ID).from(MEDIA).fetch(MEDIA.ID)).containsExactlyElementsOf(mediaIds);
    }

    @Test
    void bodyChangeIncrementsRevisionOnce() {
        long channelId = insertChannel();
        messageRepository.ingest(channelId, messageWithOneMedia());

        IngestResult result = messageRepository.ingest(channelId, message(
            false,
            "Updated body",
            BodyFormat.HTML,
            hash(4),
            List.of(media("media-1", 0, hash(2)))
        ));

        assertChangedAtRevisionOne(result);
        var stored = dslContext.selectFrom(MESSAGES).fetchSingle();
        assertThat(stored.getBody()).isEqualTo("Updated body");
        assertThat(stored.getSourceBodyHash()).containsExactly(hash(4));
    }

    @Test
    void formattingOnlyHashChangeIncrementsRevision() {
        long channelId = insertChannel();
        messageRepository.ingest(channelId, messageWithOneMedia());

        IngestResult result = messageRepository.ingest(channelId, message(
            false,
            "Hello, Moera",
            BodyFormat.HTML,
            hash(5),
            List.of(media("media-1", 0, hash(2)))
        ));

        assertChangedAtRevisionOne(result);
        assertThat(dslContext.select(MESSAGES.SOURCE_BODY_HASH).from(MESSAGES).fetchSingle(MESSAGES.SOURCE_BODY_HASH))
            .containsExactly(hash(5));
    }

    @Test
    void mediaHashChangeIncrementsRevisionAndUpdatesExistingMedia() {
        long channelId = insertChannel();
        messageRepository.ingest(channelId, messageWithOneMedia());
        long mediaId = dslContext.select(MEDIA.ID).from(MEDIA).fetchSingle(MEDIA.ID);

        IngestResult result = messageRepository.ingest(channelId, message(
            false,
            "Hello, Moera",
            BodyFormat.HTML,
            hash(1),
            List.of(media("media-1", 0, hash(6)))
        ));

        assertChangedAtRevisionOne(result);
        var stored = dslContext.selectFrom(MEDIA).fetchSingle();
        assertThat(stored.getId()).isEqualTo(mediaId);
        assertThat(stored.getSourceHash()).containsExactly(hash(6));
    }

    @Test
    void mediaAdditionIncrementsRevision() {
        long channelId = insertChannel();
        messageRepository.ingest(channelId, messageWithOneMedia());

        IngestResult result = messageRepository.ingest(channelId, message(
            false,
            "Hello, Moera",
            BodyFormat.HTML,
            hash(1),
            List.of(media("media-1", 0, hash(2)), media("media-2", 1, hash(7)))
        ));

        assertChangedAtRevisionOne(result);
        assertThat(dslContext.select(MEDIA.SOURCE_ID).from(MEDIA).orderBy(MEDIA.POSITION).fetch(MEDIA.SOURCE_ID))
            .containsExactly("media-1", "media-2");
    }

    @Test
    void mediaRemovalIncrementsRevision() {
        long channelId = insertChannel();
        messageRepository.ingest(channelId, message(
            false,
            "Hello, Moera",
            BodyFormat.HTML,
            hash(1),
            List.of(media("media-1", 0, hash(2)), media("media-2", 1, hash(7)))
        ));

        IngestResult result = messageRepository.ingest(channelId, messageWithOneMedia());

        assertChangedAtRevisionOne(result);
        assertThat(dslContext.select(MEDIA.SOURCE_ID).from(MEDIA).fetch(MEDIA.SOURCE_ID))
            .containsExactly("media-1");
    }

    @Test
    void deletionStateChangeIncrementsRevisionOnlyOnce() {
        long channelId = insertChannel();
        messageRepository.ingest(channelId, messageWithOneMedia());
        SourceMessage deletedMessage = message(
            true,
            "Hello, Moera",
            BodyFormat.HTML,
            hash(1),
            List.of(media("media-1", 0, hash(2)))
        );

        IngestResult changed = messageRepository.ingest(channelId, deletedMessage);
        IngestResult unchanged = messageRepository.ingest(channelId, deletedMessage);

        assertChangedAtRevisionOne(changed);
        assertThat(unchanged.revision()).isEqualTo(1);
        assertThat(unchanged.changed()).isFalse();
        assertThat(dslContext.select(MESSAGES.DELETED).from(MESSAGES).fetchSingle(MESSAGES.DELETED)).isTrue();
    }

    @Test
    void marksMessageDeletedOnlyOnFirstTransition() {
        long channelId = insertChannel();
        messageRepository.ingest(channelId, messageWithOneMedia());

        boolean changed = messageRepository.markDeleted(channelId, "message-1");
        boolean unchanged = messageRepository.markDeleted(channelId, "message-1");

        assertThat(changed).isTrue();
        assertThat(unchanged).isFalse();
        var stored = dslContext.selectFrom(MESSAGES).fetchSingle();
        assertThat(stored.getDeleted()).isTrue();
        assertThat(stored.getRevision()).isEqualTo(1);
    }

    @Test
    void changedMessagePropagatesRevisionAndSynchronizesPublicationMedia() {
        long channelId = insertChannel();
        long mirrorId = insertMirror(channelId);
        messageRepository.ingest(channelId, message(
            false,
            "Original body",
            BodyFormat.HTML,
            hash(1),
            List.of(
                media("media-changed", 0, hash(2)),
                media("media-removed", 1, hash(3))
            )
        ));
        long messageId = dslContext.select(MESSAGES.ID).from(MESSAGES).fetchSingle(MESSAGES.ID);
        long changedMediaId = dslContext.select(MEDIA.ID).from(MEDIA)
            .where(MEDIA.SOURCE_ID.eq("media-changed"))
            .fetchSingle(MEDIA.ID);
        long removedMediaId = dslContext.select(MEDIA.ID).from(MEDIA)
            .where(MEDIA.SOURCE_ID.eq("media-removed"))
            .fetchSingle(MEDIA.ID);
        long publicationId = insertPublication(mirrorId, 4, 6, "old failure");
        dslContext.insertInto(PUBLICATION_MESSAGES)
            .set(PUBLICATION_MESSAGES.PUBLICATION_ID, publicationId)
            .set(PUBLICATION_MESSAGES.MESSAGE_ID, messageId)
            .set(PUBLICATION_MESSAGES.POSITION, 0)
            .execute();
        UUID processingToken = UUID.randomUUID();
        dslContext.insertInto(PUBLICATION_MEDIA)
            .set(PUBLICATION_MEDIA.PUBLICATION_ID, publicationId)
            .set(PUBLICATION_MEDIA.MEDIA_ID, changedMediaId)
            .set(PUBLICATION_MEDIA.STATUS, UPLOADED)
            .set(PUBLICATION_MEDIA.DESTINATION_MEDIA_ID, "destination-media")
            .set(PUBLICATION_MEDIA.DESTINATION_DATA,
                jooqMappings.json(JsonNodeFactory.instance.objectNode().put("remote", "old")))
            .set(PUBLICATION_MEDIA.ATTEMPTS, 3)
            .set(PUBLICATION_MEDIA.LAST_ERROR, "old media failure")
            .set(PUBLICATION_MEDIA.PROCESSING_TOKEN, processingToken)
            .set(PUBLICATION_MEDIA.PROCESSING_UNTIL, jooqMappings.offsetDateTime(Instant.now().plusSeconds(300)))
            .execute();
        dslContext.insertInto(PUBLICATION_MEDIA)
            .set(PUBLICATION_MEDIA.PUBLICATION_ID, publicationId)
            .set(PUBLICATION_MEDIA.MEDIA_ID, removedMediaId)
            .execute();

        IngestResult result = messageRepository.ingest(channelId, message(
            false,
            "Changed body",
            BodyFormat.HTML,
            hash(4),
            List.of(
                media("media-changed", 0, hash(5)),
                media("media-added", 1, hash(6))
            )
        ));

        assertThat(result.revision()).isEqualTo(1);
        assertThat(dslContext.select(MESSAGES.REVISION).from(MESSAGES).fetchSingle(MESSAGES.REVISION)).isEqualTo(1);
        var publication = dslContext.selectFrom(PUBLICATIONS).fetchSingle();
        assertThat(publication.getRevision()).isEqualTo(5);
        assertThat(publication.getAttempts()).isZero();
        assertThat(publication.getLastError()).isNull();
        assertThat(publication.getNextAttemptAt()).isNotNull();
        var changedDelivery = dslContext.selectFrom(PUBLICATION_MEDIA)
            .where(PUBLICATION_MEDIA.MEDIA_ID.eq(changedMediaId))
            .fetchSingle();
        assertThat(changedDelivery.getStatus()).isEqualTo(org.moera.importer.persistence.jooq.enums.PublicationMediaStatus.PENDING);
        assertThat(changedDelivery.getProcessingToken()).isNull();
        assertThat(changedDelivery.getProcessingUntil()).isNull();
        assertThat(changedDelivery.getDestinationMediaId()).isNull();
        assertThat(jooqMappings.jsonNode(changedDelivery.getDestinationData()))
            .isEqualTo(JsonNodeFactory.instance.objectNode());
        assertThat(changedDelivery.getAttempts()).isZero();
        assertThat(changedDelivery.getLastError()).isNull();
        assertThat(changedDelivery.getNextAttemptAt()).isNotNull();
        long addedMediaId = dslContext.select(MEDIA.ID).from(MEDIA)
            .where(MEDIA.SOURCE_ID.eq("media-added"))
            .fetchSingle(MEDIA.ID);
        var addedDelivery = dslContext.selectFrom(PUBLICATION_MEDIA)
            .where(PUBLICATION_MEDIA.MEDIA_ID.eq(addedMediaId))
            .fetchSingle();
        assertThat(addedDelivery.getStatus()).isEqualTo(org.moera.importer.persistence.jooq.enums.PublicationMediaStatus.PENDING);
        assertThat(addedDelivery.getNextAttemptAt()).isNotNull();
        assertThat(dslContext.select(PUBLICATION_MEDIA.MEDIA_ID)
            .from(PUBLICATION_MEDIA)
            .where(PUBLICATION_MEDIA.MEDIA_ID.eq(removedMediaId))
            .fetchOptional()).isEmpty();
        assertThat(dslContext.fetchCount(PUBLICATION_MEDIA)).isEqualTo(2);
    }

    @Test
    void markDeletedMarksPublicationDirty() {
        long channelId = insertChannel();
        long mirrorId = insertMirror(channelId);
        messageRepository.ingest(channelId, messageWithOneMedia());
        long messageId = dslContext.select(MESSAGES.ID).from(MESSAGES).fetchSingle(MESSAGES.ID);
        long publicationId = insertPublication(mirrorId, 2, 4, "old failure");
        dslContext.insertInto(PUBLICATION_MESSAGES)
            .set(PUBLICATION_MESSAGES.PUBLICATION_ID, publicationId)
            .set(PUBLICATION_MESSAGES.MESSAGE_ID, messageId)
            .set(PUBLICATION_MESSAGES.POSITION, 0)
            .execute();

        boolean changed = messageRepository.markDeleted(channelId, "message-1");

        assertThat(changed).isTrue();
        assertThat(dslContext.select(MESSAGES.REVISION).from(MESSAGES).fetchSingle(MESSAGES.REVISION)).isEqualTo(1);
        var publication = dslContext.selectFrom(PUBLICATIONS).fetchSingle();
        assertThat(publication.getRevision()).isEqualTo(3);
        assertThat(publication.getAttempts()).isZero();
        assertThat(publication.getLastError()).isNull();
        assertThat(publication.getNextAttemptAt()).isNotNull();
    }

    @Test
    void doesNotMarkMissingMessageDeleted() {
        long channelId = insertChannel();

        boolean changed = messageRepository.markDeleted(channelId, "missing-message");

        assertThat(changed).isFalse();
    }

    @Test
    void metadataOnlyRefreshDoesNotIncrementRevision() {
        long channelId = insertChannel();
        messageRepository.ingest(channelId, messageWithOneMedia());
        SourceMessage refreshed = new SourceMessage(
            "message-1",
            false,
            Instant.parse("2026-08-25T10:16:30Z"),
            "Hello, Moera",
            BodyFormat.HTML,
            hash(1),
            JsonNodeFactory.instance.objectNode().put("source", "refreshed"),
            List.of(new SourceMedia(
                "media-1",
                0,
                "image/png",
                "renamed.png",
                500L,
                hash(2),
                JsonNodeFactory.instance.objectNode().put("remote", "refreshed")
            ))
        );

        IngestResult result = messageRepository.ingest(channelId, refreshed);

        assertThat(result.revision()).isZero();
        assertThat(result.changed()).isFalse();
        var storedMessage = dslContext.selectFrom(MESSAGES).fetchSingle();
        assertThat(storedMessage.getPublishedAt().toInstant()).isEqualTo(refreshed.publishedAt());
        assertThat(jooqMappings.jsonNode(storedMessage.getSourceData()).path("source").asText())
            .isEqualTo("refreshed");
        var storedMedia = dslContext.selectFrom(MEDIA).fetchSingle();
        assertThat(storedMedia.getMimeType()).isEqualTo("image/png");
        assertThat(storedMedia.getFileName()).isEqualTo("renamed.png");
        assertThat(storedMedia.getSize()).isEqualTo(500L);
    }

    @Test
    void timestampPrecisionRoundTripDoesNotIncrementRevision() {
        long channelId = insertChannel();
        Instant publishedAt = Instant.parse("2026-08-25T10:15:30.123456789Z");
        SourceMessage message = new SourceMessage(
            "message-1",
            false,
            publishedAt,
            "Hello, Moera",
            BodyFormat.HTML,
            hash(1),
            JsonNodeFactory.instance.objectNode().put("number", 1.0),
            List.of()
        );
        messageRepository.ingest(channelId, message);

        IngestResult result = messageRepository.ingest(channelId, message);

        assertThat(result.revision()).isZero();
        assertThat(result.changed()).isFalse();
    }

    @Test
    void synchronizesSparseMediaReorderingWithoutPositionCollision() {
        long channelId = insertChannel();
        messageRepository.ingest(channelId, message(
            false,
            "Hello, Moera",
            BodyFormat.HTML,
            hash(1),
            List.of(media("media-1", 0, hash(2)), media("media-2", 1, hash(3)))
        ));

        IngestResult result = messageRepository.ingest(channelId, message(
            false,
            "Hello, Moera",
            BodyFormat.HTML,
            hash(1),
            List.of(media("media-1", 5, hash(2)), media("media-2", 0, hash(3)))
        ));

        assertChangedAtRevisionOne(result);
        assertThat(dslContext.select(MEDIA.SOURCE_ID).from(MEDIA).orderBy(MEDIA.POSITION).fetch(MEDIA.SOURCE_ID))
            .containsExactly("media-2", "media-1");
    }

    @Test
    void concurrentIdenticalFirstIngestIsIdempotent() throws Exception {
        long channelId = insertChannel();
        installInsertDelay();
        CyclicBarrier start = new CyclicBarrier(2);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await();
                return messageRepository.ingest(channelId, messageWithOneMedia());
            });
            var second = executor.submit(() -> {
                start.await();
                return messageRepository.ingest(channelId, messageWithOneMedia());
            });

            List<IngestResult> results = List.of(first.get(), second.get());
            assertThat(results).extracting(IngestResult::messageId).containsOnly(results.getFirst().messageId());
            assertThat(results).extracting(IngestResult::created).containsExactlyInAnyOrder(true, false);
            assertThat(results).extracting(IngestResult::revision).containsOnly(0);
        }
    }

    @Test
    void concurrentDifferingFirstIngestSerializesRevisions() throws Exception {
        long channelId = insertChannel();
        installInsertDelay();
        CyclicBarrier start = new CyclicBarrier(2);
        SourceMessage firstMessage = messageWithOneMedia();
        SourceMessage secondMessage = message(
            false,
            "Updated body",
            BodyFormat.HTML,
            hash(4),
            List.of(media("media-1", 0, hash(2)))
        );

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await();
                return messageRepository.ingest(channelId, firstMessage);
            });
            var second = executor.submit(() -> {
                start.await();
                return messageRepository.ingest(channelId, secondMessage);
            });

            List<IngestResult> results = List.of(first.get(), second.get());
            assertThat(results).extracting(IngestResult::messageId).containsOnly(results.getFirst().messageId());
            assertThat(results).extracting(IngestResult::revision).containsExactlyInAnyOrder(0, 1);
            assertThat(dslContext.select(MESSAGES.REVISION).from(MESSAGES).fetchSingle(MESSAGES.REVISION)).isEqualTo(1);
        }
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

    private long insertMirror(long channelId) {
        long destinationId = dslContext.insertInto(DESTINATIONS)
            .set(DESTINATIONS.NODE_NAME, "node.moera.org")
            .returningResult(DESTINATIONS.ID)
            .fetchSingle(DESTINATIONS.ID);
        return dslContext.insertInto(MIRRORS)
            .set(MIRRORS.CHANNEL_ID, channelId)
            .set(MIRRORS.DESTINATION_ID, destinationId)
            .returningResult(MIRRORS.ID)
            .fetchSingle(MIRRORS.ID);
    }

    private long insertPublication(long mirrorId, int revision, int attempts, String lastError) {
        return dslContext.insertInto(PUBLICATIONS)
            .set(PUBLICATIONS.MIRROR_ID, mirrorId)
            .set(PUBLICATIONS.REVISION, revision)
            .set(PUBLICATIONS.PUBLISHED_REVISION, revision)
            .set(PUBLICATIONS.ATTEMPTS, attempts)
            .set(PUBLICATIONS.LAST_ERROR, lastError)
            .setNull(PUBLICATIONS.NEXT_ATTEMPT_AT)
            .returningResult(PUBLICATIONS.ID)
            .fetchSingle(PUBLICATIONS.ID);
    }

    private byte[] hash(int firstByte) {
        byte[] hash = new byte[32];
        hash[0] = (byte) firstByte;
        return hash;
    }

    private SourceMessage messageWithOneMedia() {
        return message(
            false,
            "Hello, Moera",
            BodyFormat.HTML,
            hash(1),
            List.of(media("media-1", 0, hash(2)))
        );
    }

    private SourceMessage message(
        boolean deleted,
        String body,
        BodyFormat bodyFormat,
        byte[] bodyHash,
        List<SourceMedia> media
    ) {
        return new SourceMessage(
            "message-1",
            deleted,
            Instant.parse("2026-08-25T10:15:30Z"),
            body,
            bodyFormat,
            bodyHash,
            JsonNodeFactory.instance.objectNode().put("source", "message"),
            media
        );
    }

    private SourceMedia media(String sourceId, int position, byte[] sourceHash) {
        return new SourceMedia(
            sourceId,
            position,
            "image/jpeg",
            sourceId + ".jpg",
            100L + position,
            sourceHash,
            JsonNodeFactory.instance.objectNode().put("remote", sourceId)
        );
    }

    private void installInsertDelay() {
        dslContext.execute("""
            CREATE FUNCTION delay_message_insert() RETURNS trigger
            LANGUAGE plpgsql AS $$
            BEGIN
                PERFORM pg_sleep(1);
                RETURN NEW;
            END
            $$
            """);
        dslContext.execute("""
            CREATE TRIGGER delay_message_insert
            BEFORE INSERT ON messages
            FOR EACH ROW EXECUTE FUNCTION delay_message_insert()
            """);
    }

    private void assertChangedAtRevisionOne(IngestResult result) {
        assertThat(result.revision()).isEqualTo(1);
        assertThat(result.created()).isFalse();
        assertThat(result.changed()).isTrue();
    }

}
