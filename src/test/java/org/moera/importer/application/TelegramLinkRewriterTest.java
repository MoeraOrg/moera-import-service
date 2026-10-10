package org.moera.importer.application;

import java.util.Map;

import org.jsoup.Jsoup;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.moera.importer.source.telegram.TelegramMessageUrl;
import org.moera.lib.node.types.SourceFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.moera.lib.node.types.SourceFormat.HTML;
import static org.moera.lib.node.types.SourceFormat.MARKDOWN;
import static org.moera.lib.node.types.SourceFormat.PLAIN_TEXT;

class TelegramLinkRewriterTest {

    private static final String MOERA_URL = "https://moera.page/@alice/~/post/target";
    private static final Map<TelegramMessageUrl, String> TARGETS = Map.of(
        new TelegramMessageUrl("news", 42), MOERA_URL
    );

    @Test
    void replacesHtmlLinkDestinationsAndVisibleUrlsWithoutDamagingMarkup() {
        String html = "<p><a href='https://telegram.me/s/News/42?single&amp;foo=1'>"
            + "https://t.me/news/42</a> &amp; <b>name</b></p>";

        String result = TelegramLinkRewriter.rewrite(html, HTML, TARGETS);

        var body = Jsoup.parseBodyFragment(result).body();
        assertThat(body.selectFirst("a").attr("href")).isEqualTo(MOERA_URL);
        assertThat(body.selectFirst("a").text()).isEqualTo(MOERA_URL);
        assertThat(body.selectFirst("b").text()).isEqualTo("name");
        assertThat(body.text()).isEqualTo(MOERA_URL + " & name");
        assertThat(TelegramLinkRewriter.collect(html, HTML)).containsExactly(new TelegramMessageUrl("news", 42));
    }

    @Test
    void preservesNamedAnchorText() {
        String result = TelegramLinkRewriter.rewrite(
            "<a href=\"https://t.me/news/42\">Read this</a>", HTML, TARGETS
        );

        assertThat(Jsoup.parseBodyFragment(result).selectFirst("a").text()).isEqualTo("Read this");
    }

    @Test
    void replacesWholeHtmlDestinationIncludingQueryPunctuation() {
        String result = TelegramLinkRewriter.rewrite(
            "<a href=\"https://t.me/news/42?text=hello.\">Read this</a>", HTML, TARGETS
        );

        assertThat(Jsoup.parseBodyFragment(result).selectFirst("a").attr("href")).isEqualTo(MOERA_URL);
    }

    @Test
    void doesNotTreatPunctuationInsideHrefAsSentenceDelimiter() {
        String html = "<a href=\"https://t.me/news/42.\">Read this</a>";

        assertThat(TelegramLinkRewriter.rewrite(html, HTML, TARGETS)).isEqualTo(html);
    }

    @Test
    void replacesMarkdownLinksAndPlainUrlsWithPunctuation() {
        String text = "[read](https://t.me/news/42) and <http://telegram.me/news/42>; "
            + "(https://t.me/news/42), https://t.me/news/42.";

        assertThat(TelegramLinkRewriter.rewrite(text, MARKDOWN, TARGETS))
            .isEqualTo("[read](" + MOERA_URL + ") and <" + MOERA_URL + ">; ("
                + MOERA_URL + "), " + MOERA_URL + ".");
    }

    @Test
    void rewritesVisibleMarkdownUrlsWithoutChangingTheirDelimiters() {
        String text = "[https://t.me/news/42](https://t.me/news/42) **https://t.me/news/42** "
            + "`https://t.me/news/42` _https://t.me/news/42_";

        assertThat(TelegramLinkRewriter.rewrite(text, MARKDOWN, TARGETS))
            .isEqualTo("[" + MOERA_URL + "](" + MOERA_URL + ") **" + MOERA_URL + "** `"
                + MOERA_URL + "` _" + MOERA_URL + "_");
    }

    @Test
    void rewritesMarkdownReferenceLinks() {
        String text = "[https://t.me/news/42][source]\n\n[source]: https://t.me/news/42 \"title\"";

        assertThat(TelegramLinkRewriter.rewrite(text, MARKDOWN, TARGETS))
            .isEqualTo("[" + MOERA_URL + "][source]\n\n[source]: " + MOERA_URL + " \"title\"");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "[]" })
    void preservesShortcutMarkdownReferenceAssociations(String suffix) {
        String text = "[https://t.me/news/42]" + suffix + "\n\n[https://t.me/news/42]: https://t.me/news/42";
        String result = TelegramLinkRewriter.rewrite(text, MARKDOWN, TARGETS);
        String html = HtmlRenderer.builder().build().render(Parser.builder().build().parse(result));

        assertThat(Jsoup.parseBodyFragment(html).select("a")).singleElement().satisfies(anchor -> {
            assertThat(anchor.attr("href")).isEqualTo(MOERA_URL);
            assertThat(anchor.text()).isEqualTo(MOERA_URL);
        });
    }

    @Test
    void doesNotMergeDistinctMarkdownReferenceIdentifiersAfterGrouping() {
        Map<TelegramMessageUrl, String> targets = Map.of(
            new TelegramMessageUrl("news", 42), MOERA_URL, new TelegramMessageUrl("news", 43), MOERA_URL
        );
        String text = "[https://t.me/news/42] [https://t.me/news/43]\n\n"
            + "[https://t.me/news/42]: https://first.example/\n"
            + "[https://t.me/news/43]: https://second.example/";
        String result = TelegramLinkRewriter.rewrite(text, MARKDOWN, targets);
        String html = HtmlRenderer.builder().build().render(Parser.builder().build().parse(result));

        assertThat(Jsoup.parseBodyFragment(html).select("a").eachAttr("href"))
            .containsExactly("https://first.example/", "https://second.example/");
        assertThat(Jsoup.parseBodyFragment(html).select("a").eachText()).containsExactly(MOERA_URL, MOERA_URL);
    }

    @Test
    void decodesMarkdownUrlEscapesAndEntitiesForLookup() {
        Map<TelegramMessageUrl, String> targets = Map.of(
            new TelegramMessageUrl("news_channel", 42), MOERA_URL, new TelegramMessageUrl("news", 42), MOERA_URL
        );
        String text = "[read](https://t.me/news\\_channel/42) [deep](tg://resolve?domain=news&amp;post=42)";

        assertThat(TelegramLinkRewriter.rewrite(text, MARKDOWN, targets))
            .isEqualTo("[read](" + MOERA_URL + ") [deep](" + MOERA_URL + ")");
    }

    @Test
    void rewritesUrlsInMarkdownFencedCodeBlocks() {
        String text = "```text\nhttps://t.me/news/42\n```";

        assertThat(TelegramLinkRewriter.rewrite(text, MARKDOWN, TARGETS))
            .isEqualTo("```text\n" + MOERA_URL + "\n```");
    }

    @Test
    void rewritesInlineHtmlLinkDestinationsInMarkdown() {
        String text = "<a href=\"https://t.me/news/42\">Read this</a>";

        assertThat(TelegramLinkRewriter.rewrite(text, MARKDOWN, TARGETS))
            .isEqualTo("<a href=\"" + MOERA_URL + "\">Read this</a>");
    }

    @Test
    void rewritesRawHtmlBlocksInMarkdown() {
        String text = "<p>https://t.me/news/42</p>";

        assertThat(TelegramLinkRewriter.rewrite(text, MARKDOWN, TARGETS)).isEqualTo("<p>" + MOERA_URL + "</p>");
    }

    @Test
    void preservesAnUnrelatedMarkdownDestinationAsAWhole() {
        String text = "[read](https://example.org/?url=https://t.me/news/42)";

        assertThat(TelegramLinkRewriter.rewrite(text, MARKDOWN, TARGETS)).isEqualTo(text);
    }

    @ParameterizedTest
    @EnumSource(value = SourceFormat.class, names = { "HTML", "MARKDOWN", "PLAIN_TEXT" })
    void doesNotRewriteTelegramUrlsInsideAnUnrelatedUrl(SourceFormat format) {
        String text = "https://example.org/?url=https://t.me/news/42";

        assertThat(TelegramLinkRewriter.rewrite(text, format, TARGETS)).isEqualTo(text);
        assertThat(TelegramLinkRewriter.collect(text, format)).isEmpty();
    }

    @Test
    void rewritesUrlsSplitAcrossInlineHtmlElements() {
        String html = "<p><a href=\"https://t.me/news/42\">https://t.me/<b>news</b>/42</a></p>";

        var result = Jsoup.parseBodyFragment(TelegramLinkRewriter.rewrite(html, HTML, TARGETS));

        assertThat(result.selectFirst("a").attr("href")).isEqualTo(MOERA_URL);
        assertThat(result.selectFirst("a").text()).isEqualTo(MOERA_URL);
        assertThat(result.select("b")).hasSize(1);
    }

    @Test
    void rewritesFormattedHtmlUrlsWithinSurroundingText() {
        String html = "<p>Read https://t.me/<b>news</b>/42 <em>now</em></p>";

        var result = Jsoup.parseBodyFragment(TelegramLinkRewriter.rewrite(html, HTML, TARGETS));

        assertThat(result.text()).isEqualTo("Read " + MOERA_URL + " now");
        assertThat(result.selectFirst("em").text()).isEqualTo("now");
        assertThat(TelegramLinkRewriter.collect(html, HTML)).containsExactly(new TelegramMessageUrl("news", 42));
    }

    @Test
    void doesNotJoinUrlsAcrossSeparateHtmlParagraphs() {
        String html = "<p>https://t.me/</p><p>news/42</p>";

        assertThat(TelegramLinkRewriter.collect(html, HTML)).isEmpty();
        assertThat(TelegramLinkRewriter.rewrite(html, HTML, TARGETS)).isEqualTo(html);
    }

    @Test
    void doesNotReplaceAnIdPrefixInsideAnotherMessageUrl() {
        String text = "https://t.me/news/420 https://t.me/news/42/7 https://t.me/news/42abc";

        assertThat(TelegramLinkRewriter.rewrite(text, PLAIN_TEXT, TARGETS)).isEqualTo(text);
    }

    @Test
    void collectsPrivateAndDeepLinksWithoutResolvingChannelLinks() {
        String text = "https://t.me/c/123/42 tg://resolve?domain=news&post=42 https://t.me/news";

        assertThat(TelegramLinkRewriter.collect(text, PLAIN_TEXT)).containsExactlyInAnyOrder(
            new TelegramMessageUrl("c/123", 42), new TelegramMessageUrl("news", 42)
        );
    }

    @ParameterizedTest
    @EnumSource(value = SourceFormat.class, names = { "HTML", "MARKDOWN", "PLAIN_TEXT" })
    void preservesOriginalContentWhenThereIsNoMapping(SourceFormat format) {
        String text = "<p>  <a href='https://t.me/unknown/42'>read</a> &amp; https://t.me/news</p>";

        assertThat(TelegramLinkRewriter.rewrite(text, format, TARGETS)).isEqualTo(text);
    }

    @Test
    void keepsNonTelegramLinksAndInvitationLinks() {
        String text = "https://example.org/news/42 https://t.me/+secret https://t.me.evil.example/news/42";

        assertThat(TelegramLinkRewriter.rewrite(text, PLAIN_TEXT, TARGETS)).isEqualTo(text);
    }

    @Test
    void rewritesBareMessageLinksAndLeavesOtherBareDomainsUnchanged() {
        String text = "t.me/news/42 news.t.me/42 telegram.dog/news/42 evil-t.me/news/42 user@t.me/news/42";

        assertThat(TelegramLinkRewriter.rewrite(text, PLAIN_TEXT, TARGETS))
            .isEqualTo(MOERA_URL + " " + MOERA_URL + " " + MOERA_URL
                + " evil-t.me/news/42 user@t.me/news/42");
    }

}
