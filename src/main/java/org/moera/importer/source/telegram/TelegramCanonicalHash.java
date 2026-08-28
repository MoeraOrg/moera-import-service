package org.moera.importer.source.telegram;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.Comparator;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import it.tdlight.jni.TdApi;
import org.moera.importer.application.Sha256;
import org.springframework.util.ObjectUtils;

public final class TelegramCanonicalHash {

    private TelegramCanonicalHash() {
    }

    public static byte[] body(TdApi.Message message) {
        return body(message, null);
    }

    static byte[] body(TdApi.Message message, JsonNode normalizedSourceData) {
        return digest(output -> {
            writeString(output, contentType(message.content));
            writeContent(output, message.content);
            writeForward(output, message.forwardInfo);
            writeString(output, blankToNull(message.authorSignature));
            writeButtons(output, message.replyMarkup);
            TdApi.FormattedText factCheck = message.factCheck == null ? null : message.factCheck.text;
            writeFormattedText(output, factCheck == null || factCheck.text == null || factCheck.text.isBlank()
                ? null
                : factCheck);
            if (normalizedSourceData != null && normalizedSourceData.size() > 0) {
                writeJson(output, normalizedSourceData);
            }
        });
    }

    public static byte[] media(TdApi.File file, JsonNode normalizedSourceData) {
        return digest(output -> {
            String uniqueId = file != null && file.remote != null ? file.remote.uniqueId : null;
            String remoteId = file != null && file.remote != null ? file.remote.id : null;
            writeString(output, ObjectUtils.isEmpty(uniqueId) ? remoteId : uniqueId);
            writeJson(output, normalizedSourceData);
        });
    }

    private static byte[] digest(Writer writer) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                writer.write(output);
            }
            return Sha256.digest(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialize Telegram canonical hash input", e);
        }
    }

    private static String contentType(TdApi.MessageContent content) {
        return content == null ? null : content.getClass().getName();
    }

    private static void writeContent(DataOutputStream output, TdApi.MessageContent content) throws IOException {
        if (content instanceof TdApi.MessageText text) {
            writeFormattedText(output, text.text);
            writePreview(output, text.linkPreview);
        } else if (content instanceof TdApi.MessagePhoto photo) {
            writeFormattedText(output, photo.caption);
        } else if (content instanceof TdApi.MessageAnimation animation) {
            writeFormattedText(output, animation.caption);
        } else if (content instanceof TdApi.MessageAudio audio) {
            writeFormattedText(output, audio.caption);
        } else if (content instanceof TdApi.MessageDocument document) {
            writeFormattedText(output, document.caption);
        } else if (content instanceof TdApi.MessageVideo video) {
            writeFormattedText(output, video.caption);
        } else if (content instanceof TdApi.MessageVoiceNote voiceNote) {
            writeFormattedText(output, voiceNote.caption);
        } else if (content instanceof TdApi.MessageSticker sticker) {
            writeString(output, sticker.sticker == null ? null : sticker.sticker.emoji);
        } else if (content instanceof TdApi.MessageAnimatedEmoji emoji) {
            writeString(output, emoji.emoji);
        } else if (content instanceof TdApi.MessagePoll poll) {
            writePoll(output, poll.poll);
        } else if (content instanceof TdApi.MessageChecklist checklist) {
            writeChecklist(output, checklist.list);
        } else if (content instanceof TdApi.MessageLocation location) {
            writeLocation(output, location.location);
        } else if (content instanceof TdApi.MessageLiveLocation location) {
            writeLocation(output, location.location == null ? null : location.location.location);
        } else if (content instanceof TdApi.MessageVenue venue) {
            writeString(output, venue.venue == null ? null : venue.venue.title);
            writeString(output, venue.venue == null ? null : venue.venue.address);
            writeLocation(output, venue.venue == null ? null : venue.venue.location);
        } else if (content instanceof TdApi.MessageContact contact) {
            writeString(output, contact.contact == null ? null : contact.contact.firstName);
            writeString(output, contact.contact == null ? null : contact.contact.lastName);
            writeString(output, contact.contact == null ? null : contact.contact.phoneNumber);
        }
    }

    private static void writePoll(DataOutputStream output, TdApi.Poll poll) throws IOException {
        if (poll == null) {
            output.writeInt(-1);
            return;
        }
        writeFormattedText(output, poll.question);
        TdApi.PollOption[] options = poll.options == null ? new TdApi.PollOption[0] : poll.options;
        output.writeInt(options.length);
        for (TdApi.PollOption option : options) {
            writeFormattedText(output, option == null ? null : option.text);
        }
        if (poll.type instanceof TdApi.PollTypeQuiz quiz) {
            int[] correct = quiz.correctOptionIds == null ? new int[0] : quiz.correctOptionIds;
            output.writeInt(correct.length);
            for (int optionId : correct) {
                output.writeInt(optionId);
            }
        } else {
            output.writeInt(0);
        }
    }

    private static void writeChecklist(DataOutputStream output, TdApi.Checklist checklist) throws IOException {
        if (checklist == null) {
            output.writeInt(-1);
            return;
        }
        writeFormattedText(output, checklist.title);
        TdApi.ChecklistTask[] tasks = checklist.tasks == null ? new TdApi.ChecklistTask[0] : checklist.tasks;
        output.writeInt(tasks.length);
        for (TdApi.ChecklistTask task : tasks) {
            output.writeBoolean(task != null);
            if (task != null) {
                output.writeInt(task.id);
                writeFormattedText(output, task.text);
                output.writeBoolean(task.completedBy != null);
            }
        }
    }

    private static void writeLocation(DataOutputStream output, TdApi.Location location) throws IOException {
        output.writeBoolean(location != null);
        if (location != null) {
            output.writeDouble(location.latitude);
            output.writeDouble(location.longitude);
        }
    }

    private static void writeForward(DataOutputStream output, TdApi.MessageForwardInfo forward) throws IOException {
        if (forward == null) {
            output.writeInt(-1);
            return;
        }
        TdApi.MessageOrigin origin = forward.origin;
        output.writeInt(origin == null ? 0 : origin.getConstructor());
        if (origin instanceof TdApi.MessageOriginHiddenUser hidden) {
            writeString(output, hidden.senderName);
        } else if (origin instanceof TdApi.MessageOriginUser user) {
            output.writeLong(user.senderUserId);
        } else if (origin instanceof TdApi.MessageOriginChat chat) {
            output.writeLong(chat.senderChatId);
        } else if (origin instanceof TdApi.MessageOriginChannel channel) {
            output.writeLong(channel.chatId);
            output.writeLong(channel.messageId);
        }
        writeString(output, forward.source == null ? null : forward.source.senderName);
    }

    private static void writeButtons(DataOutputStream output, TdApi.ReplyMarkup replyMarkup) throws IOException {
        if (!(replyMarkup instanceof TdApi.ReplyMarkupInlineKeyboard keyboard) || keyboard.rows == null) {
            output.writeInt(0);
            return;
        }
        var supported = new java.util.ArrayList<TdApi.InlineKeyboardButton>();
        for (TdApi.InlineKeyboardButton[] row : keyboard.rows) {
            if (row == null) {
                continue;
            }
            for (TdApi.InlineKeyboardButton button : row) {
                if (button != null && button.type instanceof TdApi.InlineKeyboardButtonTypeUrl url
                    && button.text != null && !button.text.isBlank() && safeUrl(url.url)) {
                    supported.add(button);
                }
            }
        }
        output.writeInt(supported.size());
        for (TdApi.InlineKeyboardButton button : supported) {
            writeString(output, button.text);
            writeString(output, ((TdApi.InlineKeyboardButtonTypeUrl) button.type).url);
        }
    }

    private static void writePreview(DataOutputStream output, TdApi.LinkPreview preview) throws IOException {
        if (preview == null) {
            output.writeBoolean(false);
            return;
        }
        output.writeBoolean(true);
        writeString(output, blankToNull(preview.siteName));
        writeString(output, preview.url);
        writeString(output, blankToNull(preview.title));
        writeString(output, blankToNull(preview.description == null ? null : preview.description.text));
        TelegramLinkPreviewImageSelector.SelectedImage image = TelegramLinkPreviewImageSelector.select(preview.type);
        writeFileIdentity(output, image == null ? null : image.file());
    }

    private static void writeFileIdentity(DataOutputStream output, TdApi.File file) throws IOException {
        if (file == null || file.remote == null || file.remote.id == null || file.remote.id.isBlank()) {
            writeString(output, null);
            return;
        }
        writeString(output, ObjectUtils.isEmpty(file.remote.uniqueId)
            ? file.remote.id
            : file.remote.uniqueId);
    }

    private static boolean safeUrl(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getScheme() != null && switch (uri.getScheme().toLowerCase(Locale.ROOT)) {
                case "http", "https", "mailto", "tel" -> true;
                default -> false;
            };
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    private static void writeFormattedText(DataOutputStream output, TdApi.FormattedText formattedText)
        throws IOException {
        if (formattedText == null) {
            output.writeInt(-1);
            return;
        }
        writeString(output, formattedText.text);
        TdApi.TextEntity[] entities = formattedText.entities == null
            ? new TdApi.TextEntity[0]
            : formattedText.entities;
        output.writeInt(entities.length);
        for (TdApi.TextEntity entity : entities) {
            output.writeInt(entity.offset);
            output.writeInt(entity.length);
            output.writeInt(entity.type.getConstructor());
            writeEntityData(output, entity.type);
        }
    }

    private static void writeEntityData(DataOutputStream output, TdApi.TextEntityType type) throws IOException {
        if (!(type instanceof TdApi.TextEntityTypePreCode)) {
            type.serialize(output);
        }
    }

    private static void writeJson(DataOutputStream output, JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            output.writeByte(0);
        } else if (node.isObject()) {
            output.writeByte(1);
            var fields = node.properties().stream()
                .sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                .toList();
            output.writeInt(fields.size());
            for (var field : fields) {
                writeString(output, field.getKey());
                writeJson(output, field.getValue());
            }
        } else if (node.isArray()) {
            output.writeByte(2);
            output.writeInt(node.size());
            for (JsonNode item : node) {
                writeJson(output, item);
            }
        } else {
            output.writeByte(3);
            writeString(output, node.toString());
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @FunctionalInterface
    private interface Writer {

        void write(DataOutputStream output) throws IOException;

    }

}
