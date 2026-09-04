package org.moera.importer.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moera.importer.config.ProfileProperties;
import org.moera.importer.source.ChannelMetadata;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelProfileRendererTest {

    private ProfileProperties properties;
    private ChannelProfileRenderer renderer;

    @BeforeEach
    void setUp() {
        properties = new ProfileProperties();
        properties.setContactEmail("import@moera.example");
        renderer = new ChannelProfileRenderer();
        ReflectionTestUtils.setField(renderer, "profileProperties", properties);
    }

    @Test
    void rendersDescriptionAndEnglishAttributionWithPublicLinks() {
        var profile = renderer.render(new ChannelMetadata(
            "News & reports",
            "<p>First paragraph<br>line two</p><p>Second &lt;paragraph&gt;</p>",
            "news_reports",
            null
        ));

        assertThat(profile.fullName()).isEqualTo("News & reports");
        assertThat(profile.bioHtml()).isEqualTo(
            "<p>First paragraph<br>line two</p><p>Second &lt;paragraph&gt;</p>"
                + "<p>This profile mirrors <a href=\"https://t.me/news_reports\">Telegram channel "
                + "&quot;News &amp; reports&quot;</a>. To import another channel, or your own channel into your Moera "
                + "account, contact <a href=\"mailto:import@moera.example\">import@moera.example</a>.</p>"
        );
    }

    @Test
    void neverExposesPrivateInviteLinkWhenNoPublicUsernameExists() {
        var profile = renderer.render(new ChannelMetadata("Private", "", null, null));

        assertThat(profile.bioHtml()).contains("Telegram channel &quot;Private&quot;");
        assertThat(profile.bioHtml()).doesNotContain("t.me/+", "href=\"https://t.me/");
    }

    @Test
    void usesConfiguredEnglishHtmlTemplate() {
        properties.setAttributionHtml("<p>Mirror: {channelLink}; requests: {contactLink}.</p>");

        var profile = renderer.render(new ChannelMetadata("News", "", "news", null));

        assertThat(profile.bioHtml()).isEqualTo(
            "<p>Mirror: <a href=\"https://t.me/news\">Telegram channel &quot;News&quot;</a>; requests: "
                + "<a href=\"mailto:import@moera.example\">import@moera.example</a>.</p>"
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
        assertThat(profile.bioHtml()).contains("</a></p><p>This profile mirrors");
    }

}
