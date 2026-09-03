package org.moera.importer.application;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.moera.importer.model.BodyFormat;
import org.moera.importer.model.Destination;
import org.moera.importer.model.Media;
import org.moera.importer.model.Message;
import org.moera.importer.moera.DestinationCapabilities;
import org.moera.importer.persistence.MessageLinkPreview;
import org.moera.importer.persistence.PublicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.moera.lib.node.types.SourceFormat.HTML;
import static org.moera.lib.node.types.SourceFormat.MARKDOWN;
import static org.moera.lib.node.types.SourceFormat.PLAIN_TEXT;

class PublicationPayloadBuilderTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    private final PublicationPayloadBuilder publicationPayloadBuilder = new PublicationPayloadBuilder();

    @Test
    void preservesCommonSupportedFormatAndJoinsAtParagraphBoundary() {
        PublicationContext context = context(
            config("timeline"),
            List.of(member(message(1, "first", BodyFormat.PLAIN_TEXT)),
                member(message(2, "second", BodyFormat.PLAIN_TEXT))),
            List.of()
        );

        PublicationPayload payload = publicationPayloadBuilder.build(
            context, capabilities(PLAIN_TEXT, HTML)
        );

        assertThat(payload.bodySrcFormat()).isEqualTo(PLAIN_TEXT);
        assertThat(payload.bodySrc().getText()).isEqualTo("first\n\nsecond");
    }

    @Test
    void convertsMixedFormatsToHtml() {
        PublicationContext context = context(
            config("timeline"),
            List.of(member(message(1, "first\nline", BodyFormat.PLAIN_TEXT)),
                member(message(2, "<p>second</p>", BodyFormat.HTML)),
                member(message(3, "**third**", BodyFormat.MARKDOWN))),
            List.of()
        );

        PublicationPayload payload = publicationPayloadBuilder.build(context, capabilities(HTML));

        assertThat(payload.bodySrcFormat()).isEqualTo(HTML);
        assertThat(payload.bodySrc().getText())
            .isEqualTo("<p>first<br>line</p><p>second</p><p><strong>third</strong></p>");
    }

    @Test
    void fallsBackToPlainTextWhenHtmlIsUnsupported() {
        PublicationContext context = context(
            config("timeline"),
            List.of(member(message(1, "<p>first<br>line</p>", BodyFormat.HTML)),
                member(message(2, "**second**", BodyFormat.MARKDOWN))),
            List.of()
        );

        PublicationPayload payload = publicationPayloadBuilder.build(context, capabilities(PLAIN_TEXT));

        assertThat(payload.bodySrcFormat()).isEqualTo(PLAIN_TEXT);
        assertThat(payload.bodySrc().getText()).isEqualTo("first\nline\n\nsecond");
    }

    @Test
    void buildsMediaOnlyPayload() {
        Media image = media(11, 1, 0);
        PublicationContext context = context(
            config("timeline"),
            List.of(member(message(1, "", BodyFormat.HTML))),
            List.of(delivered(image, "media-id", "media-hash"))
        );

        PublicationPayload payload = publicationPayloadBuilder.build(context, capabilities(HTML));

        assertThat(payload.bodySrc().getText()).isEmpty();
        assertThat(payload.media()).singleElement()
            .satisfies(attached -> assertThat(attached.getLocalMediaId()).isEqualTo("media-id"));
        assertThat(payload.empty()).isFalse();
    }

    @Test
    void usesEarliestTimeConfiguredFeedsAndEffectivePin() {
        Message laterPinned = message(1, "later", BodyFormat.HTML, NOW, true, false, objectNode());
        Message earlier = message(2, "earlier", BodyFormat.HTML, NOW.minusSeconds(60), false, false, objectNode());
        Message deletedPinned = message(3, "deleted", BodyFormat.HTML, NOW.minusSeconds(120), true, true, objectNode());
        PublicationContext context = context(
            config("timeline", "news"),
            List.of(member(laterPinned), member(earlier), member(deletedPinned)),
            List.of()
        );

        PublicationPayload payload = publicationPayloadBuilder.build(context, capabilities(HTML));

        assertThat(payload.pinned()).isTrue();
        assertThat(payload.publications()).extracting(publication -> publication.getFeedName())
            .containsExactly("timeline", "news");
        assertThat(payload.publications()).allSatisfy(publication -> {
            assertThat(publication.getPublishAt()).isEqualTo(NOW.minusSeconds(60).getEpochSecond());
            assertThat(publication.getPinned()).isTrue();
        });
        assertThat(payload.bodySrc().getText()).doesNotContain("deleted");
    }

    @Test
    void collectsOrderedSourceLinksFromNonDeletedMembers() {
        ObjectNode firstSource = objectNode().put("externalSourceUri", "https://t.me/news/10");
        ObjectNode secondSource = objectNode().put("externalSourceUri", "https://t.me/news/11");
        ObjectNode deletedSource = objectNode().put("externalSourceUri", "https://t.me/news/12");
        PublicationContext context = context(
            config("timeline"),
            List.of(
                member(message(1, "first", BodyFormat.HTML, NOW, false, false, firstSource)),
                member(message(2, "", BodyFormat.HTML, NOW, false, false, secondSource)),
                member(message(3, "deleted", BodyFormat.HTML, NOW, false, true, deletedSource))
            ),
            List.of()
        );

        PublicationPayload payload = publicationPayloadBuilder.build(context, capabilities(HTML));

        assertThat(payload.externalSourceUri())
            .containsExactly("https://t.me/news/10", "https://t.me/news/11");
    }

    @Test
    void insertsRuleBetweenDifferentForwardAttributions() {
        ObjectNode firstSource = objectNode();
        firstSource.putObject("forwardAttribution").put("name", "First");
        ObjectNode secondSource = objectNode();
        secondSource.putObject("forwardAttribution").put("name", "Second");
        PublicationContext context = context(
            config("timeline"),
            List.of(
                member(message(1, "<p>First</p><p>one</p>", BodyFormat.HTML, NOW, false, false, firstSource)),
                member(message(2, "<p>Second</p><p>two</p>", BodyFormat.HTML, NOW, false, false, secondSource))
            ),
            List.of()
        );

        PublicationPayload payload = publicationPayloadBuilder.build(context, capabilities(HTML));

        assertThat(payload.bodySrc().getText())
            .isEqualTo("<p>First</p><p>one</p><hr><p>Second</p><p>two</p>");
    }

    @Test
    void buildsOrderedDeduplicatedLinkPreviewsAndUsesUploadedHash() {
        Media firstImage = media(11, 1, 0);
        Media duplicateImage = media(12, 2, 0);
        Media secondImage = media(13, 2, 1);
        PublicationContext.Member first = member(
            message(1, "one", BodyFormat.PLAIN_TEXT),
            preview(1, 0, "https://one", "First", 11L),
            preview(1, 1, "https://same", "Original", null)
        );
        PublicationContext.Member second = member(
            message(2, "two", BodyFormat.PLAIN_TEXT),
            preview(2, 0, "https://same", "Duplicate", 12L),
            preview(2, 1, "https://two", "Second", 13L)
        );
        PublicationContext context = context(
            config("timeline"),
            List.of(first, second),
            List.of(
                delivered(firstImage, "first-id", "first-hash"),
                delivered(duplicateImage, "duplicate-id", "duplicate-hash"),
                delivered(secondImage, "second-id", "second-hash")
            )
        );

        PublicationPayload payload = publicationPayloadBuilder.build(context, capabilities(PLAIN_TEXT));

        assertThat(payload.bodySrc().getLinkPreviews()).extracting(preview -> preview.getUrl())
            .containsExactly("https://one", "https://same", "https://two");
        assertThat(payload.bodySrc().getLinkPreviews()).extracting(preview -> preview.getTitle())
            .containsExactly("First", "Original", "Second");
        assertThat(payload.bodySrc().getLinkPreviews()).extracting(preview -> preview.getImageHash())
            .containsExactly("first-hash", null, "second-hash");
        assertThat(payload.media()).extracting(media -> media.getLocalMediaId())
            .containsExactly("first-id", "second-id");
    }

    @Test
    void keepsTextualPreviewWhenItsImageWasSkipped() {
        PublicationContext context = context(
            config("timeline"),
            List.of(member(
                message(1, "one", BodyFormat.PLAIN_TEXT),
                preview(1, 0, "https://one", "First", 11L)
            )),
            List.of()
        );

        PublicationPayload payload = publicationPayloadBuilder.build(context, capabilities(PLAIN_TEXT));

        assertThat(payload.bodySrc().getLinkPreviews()).singleElement().satisfies(preview -> {
            assertThat(preview.getUrl()).isEqualTo("https://one");
            assertThat(preview.getImageHash()).isNull();
        });
        assertThat(payload.media()).isEmpty();
    }

    private DestinationCapabilities capabilities(org.moera.lib.node.types.SourceFormat... formats) {
        return new DestinationCapabilities(1000, List.of(formats));
    }

    private PublicationContext context(ObjectNode config, List<PublicationContext.Member> members,
                                       List<PublicationContext.DeliveredMedia> media) {
        Destination destination = new Destination(1, "alice", objectNode(), objectNode(), NOW, NOW);
        return new PublicationContext(7, 2, destination, config, null, objectNode(), members, media);
    }

    private ObjectNode config(String... feeds) {
        ObjectNode config = objectNode();
        var array = config.putArray("feeds");
        for (String feed : feeds) {
            array.add(feed);
        }
        return config;
    }

    private PublicationContext.Member member(Message message, MessageLinkPreview... previews) {
        return new PublicationContext.Member(message, List.of(previews));
    }

    private Message message(long id, String body, BodyFormat format) {
        return message(id, body, format, NOW.plusSeconds(id), false, false, objectNode());
    }

    private Message message(long id, String body, BodyFormat format, Instant publishedAt, boolean pinned,
                            boolean deleted, ObjectNode sourceData) {
        return new Message(
            id, 1, Long.toString(id), 0, deleted, pinned, publishedAt, body, format, new byte[] { 1 }, sourceData,
            NOW, NOW
        );
    }

    private Media media(long id, long messageId, int position) {
        return new Media(
            id, messageId, Long.toString(id), position, "image/jpeg", "image.jpg", 10L, new byte[] { 1 },
            objectNode(), NOW, NOW
        );
    }

    private PublicationContext.DeliveredMedia delivered(Media media, String id, String hash) {
        return new PublicationContext.DeliveredMedia(media, id, hash);
    }

    private MessageLinkPreview preview(long messageId, int position, String url, String title, Long imageMediaId) {
        return new MessageLinkPreview(
            messageId, position, "Example", url, title, "Description", NOW.minusSeconds(position), imageMediaId
        );
    }

    private ObjectNode objectNode() {
        return JsonNodeFactory.instance.objectNode();
    }

}
