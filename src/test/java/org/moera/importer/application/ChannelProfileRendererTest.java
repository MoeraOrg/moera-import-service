package org.moera.importer.application;

import org.junit.jupiter.api.Test;
import org.moera.importer.source.ChannelMetadata;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelProfileRendererTest {

    private final ChannelProfileRenderer renderer = new ChannelProfileRenderer();

    @Test
    void rendersChannelTitleAndDescription() {
        var profile = renderer.render(new ChannelMetadata(
            "News & reports",
            "<p>First paragraph<br>line two</p><p>Second &lt;paragraph&gt;</p>",
            "news_reports",
            null
        ));

        assertThat(profile.fullName()).isEqualTo("News & reports");
        assertThat(profile.bioHtml()).isEqualTo(
            "<p>First paragraph<br>line two</p><p>Second &lt;paragraph&gt;</p>"
        );
    }

    @Test
    void rendersDetectedDescriptionLinksButLeavesHashtagsPlain() {
        var profile = renderer.render(new ChannelMetadata(
            "News",
            "<p>Visit <a href=\"https://example.org\">https://example.org</a>, write "
                + "<a href=\"mailto:mail@example.org\">mail@example.org</a>, ask "
                + "<a href=\"https://t.me/admin\">@admin</a> and #ignored.</p>",
            "news",
            null
        ));

        assertThat(profile.bioHtml()).startsWith(
            "<p>Visit <a href=\"https://example.org\">https://example.org</a>, write "
                + "<a href=\"mailto:mail@example.org\">mail@example.org</a>, ask "
                + "<a href=\"https://t.me/admin\">@admin</a> and #ignored.</p>"
        );
        assertThat(profile.bioHtml()).doesNotContain(">#ignored</a>");
    }

    @Test
    void truncatesDescriptionWithoutBreakingHtml() {
        String description = "<p><a href=\"https://example.org\">" + "x".repeat(5000) + "</a></p>";

        var profile = renderer.render(new ChannelMetadata("News", description, "news", null));

        assertThat(profile.bioHtml().codePointCount(0, profile.bioHtml().length())).isLessThanOrEqualTo(4096);
        assertThat(profile.bioHtml()).startsWith("<p><a href=\"https://example.org\">");
        assertThat(profile.bioHtml()).endsWith("</a></p>");
    }

}
