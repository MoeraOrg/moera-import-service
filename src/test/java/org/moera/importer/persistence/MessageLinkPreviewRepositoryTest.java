package org.moera.importer.persistence;

import java.time.Instant;
import java.util.List;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moera.importer.model.BodyFormat;
import org.moera.importer.source.SourceLinkPreview;
import org.moera.importer.source.SourceMedia;
import org.moera.importer.source.SourceMessage;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.moera.importer.persistence.jooq.enums.AccessState.GRANTED;
import static org.moera.importer.persistence.jooq.enums.SourceType.TELEGRAM;
import static org.moera.importer.persistence.jooq.enums.SubscriptionState.ACTIVE;
import static org.moera.importer.persistence.jooq.tables.Channels.CHANNELS;
import static org.moera.importer.persistence.jooq.tables.Media.MEDIA;
import static org.moera.importer.persistence.jooq.tables.Messages.MESSAGES;

@SpringBootTest
class MessageLinkPreviewRepositoryTest extends PostgresIntegrationTest {

    @Inject
    private DSLContext dslContext;

    @Inject
    private MessageRepository messageRepository;

    @BeforeEach
    void clearDatabase() {
        dslContext.truncate(CHANNELS).restartIdentity().cascade().execute();
    }

    @Test
    void ingestsPinnedMessageWithOrderedLinkPreviews() {
        long channelId = insertChannel();
        SourceMessage message = new SourceMessage(
            "message-1",
            false,
            true,
            Instant.parse("2026-08-25T10:15:30Z"),
            "Body",
            BodyFormat.HTML,
            hash(1),
            JsonNodeFactory.instance.objectNode(),
            List.of(media("preview-image")),
            List.of(
                new SourceLinkPreview(
                    "Example",
                    "https://example.com/first",
                    "First title",
                    "First description",
                    Instant.parse("2026-08-24T09:00:00Z"),
                    "preview-image"
                ),
                new SourceLinkPreview(
                    null,
                    "https://example.com/second",
                    null,
                    null,
                    null,
                    null
                )
            )
        );

        IngestResult result = messageRepository.ingest(channelId, message);

        long imageMediaId = dslContext.select(MEDIA.ID)
            .from(MEDIA)
            .where(MEDIA.SOURCE_ID.eq("preview-image"))
            .fetchSingle(MEDIA.ID);
        assertThat(dslContext.select(MESSAGES.PINNED).from(MESSAGES).fetchSingle(MESSAGES.PINNED)).isTrue();
        assertThat(messageRepository.findLinkPreviews(result.messageId())).containsExactly(
            new MessageLinkPreview(
                result.messageId(),
                0,
                "Example",
                "https://example.com/first",
                "First title",
                "First description",
                Instant.parse("2026-08-24T09:00:00Z"),
                imageMediaId
            ),
            new MessageLinkPreview(
                result.messageId(),
                1,
                null,
                "https://example.com/second",
                null,
                null,
                null,
                null
            )
        );
    }

    @Test
    void pinningAndPreviewChangesIncrementRevisionAndReplaceRows() {
        long channelId = insertChannel();
        SourceMessage original = message(
            false,
            List.of(media("first-image"), media("second-image", 1)),
            List.of(
                preview("https://example.com/first", "First", "first-image"),
                preview("https://example.com/second", "Second", "second-image")
            )
        );
        IngestResult created = messageRepository.ingest(channelId, original);
        SourceMessage changed = message(
            true,
            original.media(),
            List.of(
                preview("https://example.com/second", "Updated second", "first-image"),
                preview("https://example.com/first", "First", "second-image")
            )
        );

        IngestResult updated = messageRepository.ingest(channelId, changed);
        IngestResult unchanged = messageRepository.ingest(channelId, changed);

        assertThat(updated.revision()).isEqualTo(1);
        assertThat(updated.changed()).isTrue();
        assertThat(unchanged.revision()).isEqualTo(1);
        assertThat(unchanged.changed()).isFalse();
        assertThat(dslContext.select(MESSAGES.PINNED).from(MESSAGES).fetchSingle(MESSAGES.PINNED)).isTrue();
        assertThat(messageRepository.findLinkPreviews(created.messageId()))
            .extracting(MessageLinkPreview::url, MessageLinkPreview::title)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("https://example.com/second", "Updated second"),
                org.assertj.core.groups.Tuple.tuple("https://example.com/first", "First")
            );
        long firstMediaId = dslContext.select(MEDIA.ID)
            .from(MEDIA)
            .where(MEDIA.SOURCE_ID.eq("first-image"))
            .fetchSingle(MEDIA.ID);
        long secondMediaId = dslContext.select(MEDIA.ID)
            .from(MEDIA)
            .where(MEDIA.SOURCE_ID.eq("second-image"))
            .fetchSingle(MEDIA.ID);
        assertThat(messageRepository.findLinkPreviews(created.messageId()))
            .extracting(MessageLinkPreview::imageMediaId)
            .containsExactly(firstMediaId, secondMediaId);
    }

    @Test
    void removesPreviewRowsBeforeTheirMedia() {
        long channelId = insertChannel();
        messageRepository.ingest(channelId, message(
            false,
            List.of(media("preview-image")),
            List.of(preview("https://example.com", "Title", "preview-image"))
        ));

        IngestResult result = messageRepository.ingest(channelId, message(false, List.of(), List.of()));

        assertThat(result.revision()).isEqualTo(1);
        assertThat(messageRepository.findLinkPreviews(result.messageId())).isEmpty();
        assertThat(dslContext.fetchCount(MEDIA)).isZero();
    }

    @Test
    void rejectsMissingPreviewImageWithoutPersistingMessage() {
        long channelId = insertChannel();
        SourceMessage invalid = message(
            false,
            List.of(),
            List.of(preview("https://example.com", "Title", "missing-image"))
        );

        assertThatThrownBy(() -> messageRepository.ingest(channelId, invalid))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing-image");
        assertThat(dslContext.fetchCount(MESSAGES)).isZero();
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

    private SourceMedia media(String sourceId) {
        return media(sourceId, 0);
    }

    private SourceMedia media(String sourceId, int position) {
        return new SourceMedia(
            sourceId,
            position,
            "image/jpeg",
            sourceId + ".jpg",
            100L,
            hash(2),
            JsonNodeFactory.instance.objectNode()
        );
    }

    private SourceLinkPreview preview(String url, String title, String imageSourceMediaId) {
        return new SourceLinkPreview(null, url, title, null, null, imageSourceMediaId);
    }

    private SourceMessage message(
        boolean pinned,
        List<SourceMedia> media,
        List<SourceLinkPreview> linkPreviews
    ) {
        return new SourceMessage(
            "message-1",
            false,
            pinned,
            Instant.parse("2026-08-25T10:15:30Z"),
            "Body",
            BodyFormat.HTML,
            hash(1),
            JsonNodeFactory.instance.objectNode(),
            media,
            linkPreviews
        );
    }

    private byte[] hash(int firstByte) {
        byte[] hash = new byte[32];
        hash[0] = (byte) firstByte;
        return hash;
    }

}
