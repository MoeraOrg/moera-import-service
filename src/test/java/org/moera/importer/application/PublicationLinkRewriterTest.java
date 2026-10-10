package org.moera.importer.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moera.importer.model.Destination;
import org.moera.importer.persistence.PublicationLinkRepository;
import org.moera.importer.persistence.PublicationLinkRepository.PostingTarget;
import org.moera.importer.source.telegram.TelegramMessageUrl;
import org.moera.lib.node.types.SourceFormat;
import org.moera.lib.node.types.body.Body;
import org.moera.lib.node.types.body.LinkPreview;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class PublicationLinkRewriterTest {

    private static final TelegramMessageUrl SOURCE = new TelegramMessageUrl("news", 42);
    private static final Destination DESTINATION = new Destination(7, "alice_0", Instant.EPOCH, Instant.EPOCH);

    private final PublicationLinkRewriter publicationLinkRewriter = new PublicationLinkRewriter();
    private final StubPublicationLinkRepository stubPublicationLinkRepository = new StubPublicationLinkRepository();

    @BeforeEach
    void configureRewriter() {
        ReflectionTestUtils.setField(
            publicationLinkRewriter, "publicationLinkRepository", stubPublicationLinkRepository
        );
    }

    @Test
    void prefersCurrentDestinationOverOtherDeliveredCopies() {
        stubPublicationLinkRepository.targets = Map.of(SOURCE, List.of(
            new PostingTarget(1, 8, "bob_0", "remote"), new PostingTarget(1, 7, "alice_0", "local")
        ));

        assertThat(rewrite().bodySrc().getText()).isEqualTo("https://moera.page/@alice/~/post/local");
    }

    @Test
    void usesTheOnlyDeliveredCopyWhenCurrentDestinationHasNone() {
        stubPublicationLinkRepository.targets = Map.of(SOURCE, List.of(new PostingTarget(1, 8, "bob_0", "remote")));

        assertThat(rewrite().bodySrc().getText()).isEqualTo("https://moera.page/@bob/~/post/remote");
    }

    @Test
    void leavesAmbiguousRemoteCopiesUnchanged() {
        stubPublicationLinkRepository.targets = Map.of(SOURCE, List.of(
            new PostingTarget(1, 8, "bob_0", "remote"), new PostingTarget(1, 9, "carol_0", "other")
        ));

        assertThat(rewrite().bodySrc().getText()).isEqualTo("https://t.me/news/42");
    }

    @Test
    void leavesAmbiguousLocalCopiesUnchanged() {
        stubPublicationLinkRepository.targets = Map.of(SOURCE, List.of(
            new PostingTarget(1, 7, "alice_0", "local"), new PostingTarget(1, 7, "alice_0", "other")
        ));

        assertThat(rewrite().bodySrc().getText()).isEqualTo("https://t.me/news/42");
    }

    @Test
    void rewritesPreviewUrlWithoutMutatingSourcePayloadOrLosingMetadata() {
        stubPublicationLinkRepository.targets = Map.of(SOURCE, List.of(new PostingTarget(1, 7, "alice_0", "local")));
        Body sourceBody = new Body();
        sourceBody.setText("https://t.me/news/42");
        sourceBody.setSubject("Subject https://t.me/news/42");
        LinkPreview preview = new LinkPreview();
        preview.setUrl("https://t.me/news/42?text=hello.");
        preview.setSiteName("Telegram");
        preview.setTitle("See https://t.me/news/43");
        preview.setDescription("From https://t.me/news/42");
        preview.setImageHash("image-hash");
        preview.setPublishedAt(123L);
        sourceBody.setLinkPreviews(List.of(preview));
        PublicationPayload source = payload(sourceBody);

        PublicationPayload result = publicationLinkRewriter.rewrite(source, DESTINATION);

        assertThat(result.bodySrc().getSubject()).isEqualTo("Subject https://t.me/news/42");
        assertThat(result.bodySrc().getLinkPreviews()).singleElement().satisfies(rewritten -> {
            assertThat(rewritten.getUrl()).isEqualTo("https://moera.page/@alice/~/post/local");
            assertThat(rewritten.getSiteName()).isNull();
            assertThat(rewritten.getTitle()).isEqualTo("See https://t.me/news/43");
            assertThat(rewritten.getDescription()).isEqualTo("From https://t.me/news/42");
            assertThat(rewritten.getImageHash()).isEqualTo("image-hash");
            assertThat(rewritten.getPublishedAt()).isEqualTo(123L);
        });
        assertThat(source.bodySrc().getText()).isEqualTo("https://t.me/news/42");
        assertThat(preview.getUrl()).isEqualTo("https://t.me/news/42?text=hello.");
        assertThat(preview.getSiteName()).isEqualTo("Telegram");
        assertThat(result.externalSourceUri()).containsExactly("https://t.me/news/99");
        assertThat(stubPublicationLinkRepository.requested).containsExactly(SOURCE);
    }

    @Test
    void preservesPreviewSiteNameWhenOnlyBodyLinkIsRewritten() {
        stubPublicationLinkRepository.targets = Map.of(SOURCE, List.of(new PostingTarget(1, 7, "alice_0", "local")));
        Body body = new Body();
        body.setText("https://t.me/news/42");
        LinkPreview preview = new LinkPreview();
        preview.setUrl("https://t.me/news/43");
        preview.setSiteName("Telegram");
        body.setLinkPreviews(List.of(preview));

        PublicationPayload result = publicationLinkRewriter.rewrite(payload(body), DESTINATION);

        assertThat(result.bodySrc().getText()).isEqualTo("https://moera.page/@alice/~/post/local");
        assertThat(result.bodySrc().getLinkPreviews()).singleElement().satisfies(unchanged -> {
            assertThat(unchanged.getUrl()).isEqualTo("https://t.me/news/43");
            assertThat(unchanged.getSiteName()).isEqualTo("Telegram");
        });
    }

    @Test
    void preservesUnknownUrlsAndDoesNotLookUpExternalSourceMetadata() {
        assertThat(rewrite().bodySrc().getText()).isEqualTo("https://t.me/news/42");
        assertThat(stubPublicationLinkRepository.requested).containsExactly(SOURCE);
    }

    private PublicationPayload rewrite() {
        Body body = new Body();
        body.setText("https://t.me/news/42");
        return publicationLinkRewriter.rewrite(payload(body), DESTINATION);
    }

    private PublicationPayload payload(Body body) {
        return new PublicationPayload(
            body, SourceFormat.PLAIN_TEXT, List.of(), List.of(), false, List.of("https://t.me/news/99")
        );
    }

    private static class StubPublicationLinkRepository extends PublicationLinkRepository {

        private Map<TelegramMessageUrl, List<PostingTarget>> targets = Map.of();
        private Set<TelegramMessageUrl> requested;

        @Override
        public Map<TelegramMessageUrl, List<PostingTarget>> findTargets(Set<TelegramMessageUrl> urls) {
            requested = urls;
            return targets;
        }

    }

}
