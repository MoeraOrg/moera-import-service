package org.moera.importer.source.telegram;

import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramCanonicalHashTest {

    @Test
    void bodyHashIsDeterministicAndTracksTextEntitySemantics() {
        TdApi.Message original = textMessage(
            "😀text",
            new TdApi.TextEntity(2, 4, new TdApi.TextEntityTypeCustomEmoji(100))
        );
        TdApi.Message same = textMessage(
            "😀text",
            new TdApi.TextEntity(2, 4, new TdApi.TextEntityTypeCustomEmoji(100))
        );
        TdApi.Message differentEmoji = textMessage(
            "😀text",
            new TdApi.TextEntity(2, 4, new TdApi.TextEntityTypeCustomEmoji(101))
        );
        TdApi.Message differentFormatting = textMessage(
            "😀text",
            new TdApi.TextEntity(2, 4, new TdApi.TextEntityTypeBold())
        );

        assertThat(TelegramCanonicalHash.body(original)).hasSize(32)
            .containsExactly(TelegramCanonicalHash.body(same));
        assertThat(TelegramCanonicalHash.body(differentEmoji))
            .isNotEqualTo(TelegramCanonicalHash.body(original));
        assertThat(TelegramCanonicalHash.body(differentFormatting))
            .isNotEqualTo(TelegramCanonicalHash.body(original));

        TdApi.Message unknownOne = textMessage("text", new TdApi.TextEntity(0, 4, new UnknownEntityType(1)));
        TdApi.Message unknownTwo = textMessage("text", new TdApi.TextEntity(0, 4, new UnknownEntityType(2)));
        assertThat(TelegramCanonicalHash.body(unknownOne)).isNotEqualTo(TelegramCanonicalHash.body(unknownTwo));
    }

    @Test
    void bodyHashIgnoresPresentationMetadataAndPreCodeLanguage() {
        TdApi.Message first = textMessage(
            "code",
            new TdApi.TextEntity(0, 4, new TdApi.TextEntityTypePreCode("java"))
        );
        first.canBeSaved = false;
        first.editDate = 10;
        first.mediaAlbumId = 20;
        first.effectId = 30;
        TdApi.Message second = textMessage(
            "code",
            new TdApi.TextEntity(0, 4, new TdApi.TextEntityTypePreCode("kotlin"))
        );
        second.canBeSaved = true;
        second.editDate = 11;
        second.mediaAlbumId = 21;
        second.effectId = 31;

        assertThat(TelegramCanonicalHash.body(first)).containsExactly(TelegramCanonicalHash.body(second));

        second.authorSignature = "Author";
        assertThat(TelegramCanonicalHash.body(first)).isNotEqualTo(TelegramCanonicalHash.body(second));
    }

    @Test
    void bodyHashTracksSemanticDataOfTdApiEntityTypes() {
        TdApi.TextEntityTypeDateTime firstType = new TdApi.TextEntityTypeDateTime();
        firstType.unixTime = 1_700_000_000;
        TdApi.TextEntityTypeDateTime secondType = new TdApi.TextEntityTypeDateTime();
        secondType.unixTime = 1_800_000_000;

        TdApi.Message first = textMessage("tomorrow", new TdApi.TextEntity(0, 8, firstType));
        TdApi.Message second = textMessage("tomorrow", new TdApi.TextEntity(0, 8, secondType));

        assertThat(TelegramCanonicalHash.body(first)).isNotEqualTo(TelegramCanonicalHash.body(second));
    }

    @Test
    void mediaHashIncludesFileIdentityAndNormalizedSemanticData() {
        TdApi.File file = new TdApi.File();
        file.id = 123;
        file.size = 100;
        file.remote = new TdApi.RemoteFile();
        file.remote.id = "remote-id";
        file.remote.uniqueId = "unique-id";
        var sourceData = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
            .put("mimeType", "image/jpeg")
            .put("fileName", "photo.jpg");

        byte[] original = TelegramCanonicalHash.media(file, sourceData);
        file.id = 456;
        file.size = 200;

        assertThat(original).hasSize(32).containsExactly(TelegramCanonicalHash.media(file, sourceData));

        file.remote.uniqueId = "other-unique-id";
        assertThat(TelegramCanonicalHash.media(file, sourceData)).isNotEqualTo(original);
        assertThat(Arrays.equals(original, TelegramCanonicalHash.media(file, sourceData.deepCopy()
            .put("fileName", "other.jpg")))).isFalse();
    }

    @Test
    void tracksPollAnswersAndChecklistCompletionButIgnoresResultMetadata() {
        TdApi.Poll poll = new TdApi.Poll();
        poll.question = formatted("Question");
        poll.options = new TdApi.PollOption[] {option("A"), option("B")};
        poll.type = new TdApi.PollTypeQuiz(new int[0], formatted("ignored"), null);
        TdApi.Message pollMessage = message(new TdApi.MessagePoll(poll, formatted("ignored"), null, true));
        byte[] unrevealed = TelegramCanonicalHash.body(pollMessage);

        poll.type = new TdApi.PollTypeRegular();
        assertThat(TelegramCanonicalHash.body(pollMessage)).containsExactly(unrevealed);
        poll.type = new TdApi.PollTypeQuiz(new int[0], formatted("ignored"), null);

        poll.totalVoterCount = 100;
        poll.options[0].voterCount = 90;
        poll.isClosed = true;
        assertThat(TelegramCanonicalHash.body(pollMessage)).containsExactly(unrevealed);

        ((TdApi.PollTypeQuiz) poll.type).correctOptionIds = new int[] {0};
        assertThat(TelegramCanonicalHash.body(pollMessage)).isNotEqualTo(unrevealed);

        TdApi.ChecklistTask task = new TdApi.ChecklistTask(1, formatted("Task"), null, 0);
        TdApi.Checklist checklist = new TdApi.Checklist(formatted("List"), new TdApi.ChecklistTask[] {task},
            false, false, false, false);
        TdApi.Message checklistMessage = message(new TdApi.MessageChecklist(checklist));
        byte[] open = TelegramCanonicalHash.body(checklistMessage);
        checklist.canAddTasks = true;
        task.completionDate = 123;
        assertThat(TelegramCanonicalHash.body(checklistMessage)).containsExactly(open);
        task.completedBy = new TdApi.MessageSenderUser(7);
        assertThat(TelegramCanonicalHash.body(checklistMessage)).isNotEqualTo(open);
    }

    @Test
    void checklistTaskIdentityAffectsBodyHash() {
        TdApi.ChecklistTask task = new TdApi.ChecklistTask(1, formatted("Task"), null, 0);
        TdApi.Checklist checklist = new TdApi.Checklist(formatted("List"), new TdApi.ChecklistTask[] {task},
            false, false, false, false);
        TdApi.Message message = message(new TdApi.MessageChecklist(checklist));
        byte[] original = TelegramCanonicalHash.body(message);

        task.id = 2;

        assertThat(TelegramCanonicalHash.body(message)).isNotEqualTo(original);
    }

    @Test
    void tracksForwardButtonsFactCheckAndPreviewWhileIgnoringPresentation() {
        TdApi.LinkPreview preview = new TdApi.LinkPreview(
            "https://example.org", "display", "Site", "Title", formatted("Description"), "ignored author",
            new TdApi.LinkPreviewTypeArticle((TdApi.Photo) null), false, false, false, false, false, 1
        );
        TdApi.Message message = message(new TdApi.MessageText(formatted("Body"), preview,
            new TdApi.LinkPreviewOptions()));
        message.forwardInfo = new TdApi.MessageForwardInfo(new TdApi.MessageOriginHiddenUser("Origin"), 123,
            null, "ignored");
        message.replyMarkup = new TdApi.ReplyMarkupInlineKeyboard(new TdApi.InlineKeyboardButton[][] {{
            new TdApi.InlineKeyboardButton("Open", 0, null,
                new TdApi.InlineKeyboardButtonTypeUrl("https://example.org/open")),
            new TdApi.InlineKeyboardButton("Callback", 0, null,
                new TdApi.InlineKeyboardButtonTypeCallback(new byte[] {1}))
        }});
        message.factCheck = new TdApi.FactCheck(formatted("Fact"), "US");
        byte[] original = TelegramCanonicalHash.body(message);

        preview.showAboveText = true;
        preview.instantViewVersion = 99;
        message.forwardInfo.date = 456;
        message.factCheck.countryCode = "GB";
        ((TdApi.InlineKeyboardButtonTypeCallback) ((TdApi.ReplyMarkupInlineKeyboard) message.replyMarkup)
            .rows[0][1].type).data = new byte[] {2};
        assertThat(TelegramCanonicalHash.body(message)).containsExactly(original);

        preview.title = "Changed";
        assertThat(TelegramCanonicalHash.body(message)).isNotEqualTo(original);
        preview.title = "Title";
        ((TdApi.InlineKeyboardButtonTypeUrl) ((TdApi.ReplyMarkupInlineKeyboard) message.replyMarkup)
            .rows[0][0].type).url = "https://example.org/changed";
        assertThat(TelegramCanonicalHash.body(message)).isNotEqualTo(original);
    }

    @Test
    void selectedStaticPreviewThumbnailAffectsBodyHash() {
        TdApi.VideoNote videoNote = new TdApi.VideoNote();
        videoNote.video = file("ignored-video");
        videoNote.thumbnail = new TdApi.Thumbnail(new TdApi.ThumbnailFormatJpeg(), 100, 100,
            file("thumbnail-one"));
        TdApi.LinkPreview preview = new TdApi.LinkPreview(
            "https://example.org", "example.org", "Example", "Title", null, "",
            new TdApi.LinkPreviewTypeVideoNote(videoNote), false, false, false, false, false, 0
        );
        TdApi.Message message = message(new TdApi.MessageText(formatted("Body"), preview, null));
        byte[] original = TelegramCanonicalHash.body(message);

        videoNote.thumbnail.file = file("thumbnail-two");

        assertThat(TelegramCanonicalHash.body(message)).isNotEqualTo(original);
    }

    private TdApi.Message textMessage(String text, TdApi.TextEntity... entities) {
        return message(new TdApi.MessageText(new TdApi.FormattedText(text, entities), null, null));
    }

    private TdApi.Message message(TdApi.MessageContent content) {
        TdApi.Message message = new TdApi.Message();
        message.id = 42;
        message.date = 1_700_000_000;
        message.authorSignature = "";
        message.content = content;
        return message;
    }

    private TdApi.FormattedText formatted(String text) {
        return new TdApi.FormattedText(text, new TdApi.TextEntity[0]);
    }

    private TdApi.PollOption option(String text) {
        TdApi.PollOption option = new TdApi.PollOption();
        option.text = formatted(text);
        return option;
    }

    private TdApi.File file(String identity) {
        TdApi.File file = new TdApi.File();
        file.remote = new TdApi.RemoteFile(identity + "-remote", identity, false, true, 1);
        return file;
    }

    private static class UnknownEntityType extends TdApi.TextEntityType {

        private final int value;

        UnknownEntityType(int value) {
            this.value = value;
        }

        @Override
        public int getConstructor() {
            return 123456789;
        }

        @Override
        public void serialize(DataOutput dataOutput) throws IOException {
            dataOutput.writeInt(value);
        }

    }

}
