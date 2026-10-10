package org.moera.importer.source.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramMessageUrlTest {

    @Test
    void convertsPrivateChannelIdentityToTdlibChatId() {
        assertThat(TelegramMessageUrl.parse("https://t.me/c/123456/42").orElseThrow().toChannelSourceId())
            .isEqualTo("-1000000123456");
    }

    @ParameterizedTest
    @CsvSource({
        "https://t.me/News/42, news",
        "t.me/News/42, news",
        "https://telegram.dog/News/42, news",
        "https://News.t.me/42, news",
        "http://www.telegram.me/s/News/42/?single#bottom, news",
        "https://t.me/c/123456/42, c/123456",
        "https://t.me/news/7/42, news",
        "https://t.me/c/123456/7/42, c/123456",
        "tg://resolve?domain=News&post=42, news",
        "tg:resolve?domain=News&post=42, news",
        "tg://resolve?domain=News&post=42&single, news",
        "tg://privatepost?channel=123456&post=42, c/123456"
    })
    void normalizesEquivalentMessageLinks(String uri, String channel) {
        var parsed = TelegramMessageUrl.parse(uri);
        assertThat(parsed).isPresent();
        TelegramMessageUrl url = parsed.orElseThrow();

        assertThat(url.channel()).isEqualTo(channel);
        assertThat(url.messageId()).isEqualTo(42);
        assertThat(url.toUri()).isEqualTo("https://t.me/" + channel + "/42");
        assertThat(url.toSourceId()).isEqualTo("44040192");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "https://t.me/news", "https://t.me/+secret", "https://t.me/joinchat/secret",
        "https://t.me/news/0", "https://t.me/news/-1", "https://t.me/news/8796093022208",
        "https://t.me/news/42/43/44", "https://t.me/c/123456/42/43/44",
        "https://t.me/news/42?comment=7", "tg://resolve?domain=news&post=42&comment=7",
        "https://t.me.evil.example/news/42", "https://evil.example/t.me/news/42",
        "https://news.t.me.evil.example/42",
        "https://user@t.me/news/42", "https://t.me:444/news/42", "https://t.me/news/not-a-number",
        "tg://resolve?domain=news", "tg://join?invite=secret", "tg://resolve?domain=news&post=42&post=43",
        "https://t.me/c/0/42", "https://t.me/c/1000000000000/42", "https://t.me/c/99999999999999999999/42",
        "https://t.me/news/%ZZ"
    })
    void leavesLinksWithoutSafeMessageIdentityUnresolved(String uri) {
        assertThat(TelegramMessageUrl.parse(uri)).isEmpty();
    }

}
