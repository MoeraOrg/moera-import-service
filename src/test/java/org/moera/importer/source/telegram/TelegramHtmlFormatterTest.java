package org.moera.importer.source.telegram;

import java.io.DataOutput;
import java.io.IOException;
import java.util.stream.Stream;

import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramHtmlFormatterTest {

    private final TelegramHtmlFormatter telegramHtmlFormatter = new TelegramHtmlFormatter();

    @Test
    void escapesHtmlAndUsesParagraphsForBlankLines() {
        var text = new TdApi.FormattedText("\n<& one\ntwo\n\n\nthree\n", new TdApi.TextEntity[0]);

        assertThat(telegramHtmlFormatter.format(text))
            .isEqualTo("<p>&lt;&amp; one<br>two</p><p>three</p>");
    }

    @Test
    void usesTdlibUtf16OffsetsAndProducesValidNestingForCrossingEntities() {
        var text = formatted(
            "😀abcdef",
            entity(2, 4, new TdApi.TextEntityTypeBold()),
            entity(4, 4, new TdApi.TextEntityTypeItalic())
        );

        assertThat(telegramHtmlFormatter.format(text))
            .isEqualTo("<p>😀<b>ab<i>cd</i></b><i>ef</i></p>");
    }

    @ParameterizedTest
    @MethodSource("formattingEntities")
    void mapsFormattingEntities(TdApi.TextEntityType type, String expected) {
        assertThat(telegramHtmlFormatter.format(formatted("value", entity(0, 5, type))))
            .isEqualTo(expected);
    }

    @Test
    void choosesInlineOrBlockSpoilerFromItsContents() {
        assertThat(telegramHtmlFormatter.format(formatted(
            "secret",
            entity(0, 6, new TdApi.TextEntityTypeSpoiler())
        ))).isEqualTo("<p><mr-spoiler>secret</mr-spoiler></p>");

        assertThat(telegramHtmlFormatter.format(formatted(
            "one\n\ntwo",
            entity(0, 8, new TdApi.TextEntityTypeSpoiler())
        ))).isEqualTo("<mr-spoiler-block><p>one</p><p>two</p></mr-spoiler-block>");

        assertThat(telegramHtmlFormatter.format(formatted(
            "quote",
            entity(0, 5, new TdApi.TextEntityTypeSpoiler()),
            entity(0, 5, new TdApi.TextEntityTypeBlockQuote())
        ))).isEqualTo("<mr-spoiler-block><blockquote>quote</blockquote></mr-spoiler-block>");
    }

    @Test
    void mapsOnlySafeLinksAndKeepsOtherEntityTextVisible() {
        assertThat(telegramHtmlFormatter.format(formatted(
            "https://example.com link bad user@example.com +123 @moera plain",
            entity(0, 19, new TdApi.TextEntityTypeUrl()),
            entity(20, 4, new TdApi.TextEntityTypeTextUrl("https://example.org/?a=1&b=2")),
            entity(25, 3, new TdApi.TextEntityTypeTextUrl("javascript:alert(1)")),
            entity(29, 16, new TdApi.TextEntityTypeEmailAddress()),
            entity(46, 4, new TdApi.TextEntityTypePhoneNumber()),
            entity(51, 6, new TdApi.TextEntityTypeMention()),
            entity(58, 5, new TdApi.TextEntityTypeMentionName(42))
        ))).isEqualTo(
            "<p><a href=\"https://example.com\">https://example.com</a> "
                + "<a href=\"https://example.org/?a=1&amp;b=2\">link</a> bad "
                + "<a href=\"mailto:user@example.com\">user@example.com</a> "
                + "<a href=\"tel:+123\">+123</a> "
                + "<a href=\"https://t.me/moera\">@moera</a> plain</p>"
        );
    }

    @Test
    void normalizesFormattedPhoneNumberLinkTargets() {
        assertThat(telegramHtmlFormatter.format(formatted(
            "Call +1 555-0100",
            entity(5, 11, new TdApi.TextEntityTypePhoneNumber())
        ))).isEqualTo("<p>Call <a href=\"tel:+15550100\">+1 555-0100</a></p>");
    }

    @Test
    void keepsCustomEmojiPlainEntitiesAndUnknownEntitiesVisible() {
        assertThat(telegramHtmlFormatter.format(formatted(
            "😀 #tag $cash /command 1234 01:23 date unknown",
            entity(0, 2, new TdApi.TextEntityTypeCustomEmoji(123)),
            entity(3, 4, new TdApi.TextEntityTypeHashtag()),
            entity(8, 5, new TdApi.TextEntityTypeCashtag()),
            entity(14, 8, new TdApi.TextEntityTypeBotCommand()),
            entity(23, 4, new TdApi.TextEntityTypeBankCardNumber()),
            entity(28, 5, new TdApi.TextEntityTypeMediaTimestamp(83)),
            entity(34, 4, new TdApi.TextEntityTypeDateTime()),
            entity(39, 7, new UnknownTextEntityType())
        ))).isEqualTo("<p>😀 #tag $cash /command 1234 01:23 date unknown</p>");
    }

    @Test
    void rejectsMissingEntitiesAndEntityTypesAsInvalidInput() {
        assertThatThrownBy(() -> telegramHtmlFormatter.format(
            new TdApi.FormattedText("text", new TdApi.TextEntity[] {null})
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> telegramHtmlFormatter.format(
            new TdApi.FormattedText("text", new TdApi.TextEntity[] {new TdApi.TextEntity(0, 4, null)})
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> formattingEntities() {
        return Stream.of(
            Arguments.of(new TdApi.TextEntityTypeBold(), "<p><b>value</b></p>"),
            Arguments.of(new TdApi.TextEntityTypeItalic(), "<p><i>value</i></p>"),
            Arguments.of(new TdApi.TextEntityTypeUnderline(), "<p><u>value</u></p>"),
            Arguments.of(new TdApi.TextEntityTypeStrikethrough(), "<p><s>value</s></p>"),
            Arguments.of(new TdApi.TextEntityTypeCode(), "<p><code>value</code></p>"),
            Arguments.of(new TdApi.TextEntityTypePre(), "<pre>value</pre>"),
            Arguments.of(new TdApi.TextEntityTypePreCode("java"), "<pre>value</pre>"),
            Arguments.of(new TdApi.TextEntityTypeBlockQuote(), "<blockquote>value</blockquote>"),
            Arguments.of(new TdApi.TextEntityTypeExpandableBlockQuote(), "<blockquote>value</blockquote>")
        );
    }

    private static TdApi.FormattedText formatted(String text, TdApi.TextEntity... entities) {
        return new TdApi.FormattedText(text, entities);
    }

    private static TdApi.TextEntity entity(int offset, int length, TdApi.TextEntityType type) {
        return new TdApi.TextEntity(offset, length, type);
    }

    private static class UnknownTextEntityType extends TdApi.TextEntityType {

        @Override
        public int getConstructor() {
            return 123456789;
        }

        @Override
        public void serialize(DataOutput dataOutput) throws IOException {
        }

    }

}
