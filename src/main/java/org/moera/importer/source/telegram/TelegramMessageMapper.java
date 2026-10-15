package org.moera.importer.source.telegram;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.tdlight.jni.TdApi;
import org.moera.importer.model.BodyFormat;
import org.moera.importer.source.ForwardAttribution;
import org.moera.importer.source.SourceLinkPreview;
import org.moera.importer.source.SourceMedia;
import org.moera.importer.source.SourceMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TelegramMessageMapper {

    private static final String EXPIRED_MEDIA = "[Media is no longer available]";
    private static final Set<String> SAFE_SCHEMES = Set.of("http", "https", "mailto", "tel");
    private static final Set<String> SERVICE_CONTENT_NAMES = Set.of(
        "MessageBasicGroupChatCreate",
        "MessageCall",
        "MessageContactRegistered",
        "MessageCustomServiceAction",
        "MessageDirectMessagePriceChanged",
        "MessageGameScore",
        "MessageGroupCall",
        "MessageInviteVideoChatParticipants",
        "MessageManagedBotCreated",
        "MessagePaidMessagePriceChanged",
        "MessagePaidMessagesRefunded",
        "MessagePaymentRefunded",
        "MessagePaymentSuccessful",
        "MessagePaymentSuccessfulBot",
        "MessagePollOptionAdded",
        "MessagePollOptionDeleted",
        "MessageProximityAlertTriggered",
        "MessageScreenshotTaken",
        "MessageSuggestBirthdate",
        "MessageSuggestProfilePhoto",
        "MessageSupergroupChatCreate",
        "MessageUsersShared"
    );

    private final TelegramHtmlFormatter telegramHtmlFormatter = new TelegramHtmlFormatter();
    private final TelegramForwardMetadataResolver metadataResolver;

    public TelegramMessageMapper() {
        this(TelegramForwardMetadataResolver.EMPTY);
    }

    @Autowired
    TelegramMessageMapper(ObjectProvider<TelegramForwardMetadataResolver> metadataResolver) {
        this(metadataResolver.getIfAvailable(() -> TelegramForwardMetadataResolver.EMPTY));
    }

    TelegramMessageMapper(TelegramForwardMetadataResolver metadataResolver) {
        this.metadataResolver = Objects.requireNonNull(metadataResolver, "metadataResolver");
    }

    public TelegramMappingResult map(TdApi.Message message) {
        Objects.requireNonNull(message, "message");
        if (message.content == null) {
            return skipped(TelegramSkipReason.INVALID_CONTENT);
        }
        if (isUnsupported(message.content)) {
            return skipped(TelegramSkipReason.UNSUPPORTED_CONTENT);
        }
        if (isService(message.content)) {
            return skipped(TelegramSkipReason.SERVICE_MESSAGE);
        }
        try {
            Content content = mapContent(message.content);
            if (content == null) {
                return skipped(TelegramSkipReason.UNSUPPORTED_CONTENT);
            }
            Attribution attribution = attribution(message.forwardInfo);
            String body = decorateBody(message, content.body());
            ObjectNode sourceData = JsonNodeFactory.instance.objectNode();
            if (message.mediaAlbumId != 0) {
                sourceData.put("mediaAlbumId", message.mediaAlbumId);
            }
            if (message.forwardInfo != null
                && message.forwardInfo.origin instanceof TdApi.MessageOriginChannel origin) {
                sourceData.put("forwardOriginChatId", Long.toString(origin.chatId));
                sourceData.put("forwardOriginMessageId", Long.toString(origin.messageId));
            }
            String externalSourceUri = metadataResolver.messageLink(message.chatId, message.id);
            externalSourceUri = emptyToNull(externalSourceUri);
            ForwardAttribution forwardAttribution = attribution != null
                ? new ForwardAttribution(attribution.name(), attribution.url())
                : null;
            return new TelegramMappingResult.Mapped(new SourceMessage(
                Long.toString(message.id), false, message.isPinned, Instant.ofEpochSecond(message.date),
                body, BodyFormat.HTML,
                TelegramCanonicalHash.body(message, sourceData, externalSourceUri, forwardAttribution),
                externalSourceUri, forwardAttribution, sourceData, content.media(), content.linkPreviews()
            ));
        } catch (InvalidContentException e) {
            return skipped(TelegramSkipReason.INVALID_CONTENT);
        }
    }

    private Content mapContent(TdApi.MessageContent content) {
        if (content instanceof TdApi.MessageText text) {
            return text(text);
        } else if (content instanceof TdApi.MessagePhoto photo) {
            return media(photo.caption, largest(photo.photo), "image/jpeg", null, "photo");
        } else if (content instanceof TdApi.MessageAnimation animation) {
            require(animation.animation);
            return media(animation.caption, animation.animation.animation, animation.animation.mimeType,
                animation.animation.fileName, "animation");
        } else if (content instanceof TdApi.MessageAudio audio) {
            require(audio.audio);
            return media(audio.caption, audio.audio.audio, audio.audio.mimeType, audio.audio.fileName, "audio");
        } else if (content instanceof TdApi.MessageDocument document) {
            require(document.document);
            return media(document.caption, document.document.document, document.document.mimeType,
                document.document.fileName, "document");
        } else if (content instanceof TdApi.MessageVideo video) {
            require(video.video);
            return media(video.caption, video.video.video, video.video.mimeType, video.video.fileName, "video");
        } else if (content instanceof TdApi.MessageVideoNote videoNote) {
            require(videoNote.videoNote);
            return media(null, videoNote.videoNote.video, "video/mp4", null, "videoNote");
        } else if (content instanceof TdApi.MessageVoiceNote voiceNote) {
            require(voiceNote.voiceNote);
            return media(voiceNote.caption, voiceNote.voiceNote.voice, voiceNote.voiceNote.mimeType, null, "voiceNote");
        } else if (content instanceof TdApi.MessageSticker sticker) {
            return sticker(sticker.sticker);
        } else if (content instanceof TdApi.MessageAnimatedEmoji emoji) {
            return plain(emoji.emoji);
        } else if (content instanceof TdApi.MessagePoll poll) {
            return poll(poll.poll);
        } else if (content instanceof TdApi.MessageChecklist checklist) {
            return checklist(checklist.list);
        } else if (content instanceof TdApi.MessageLocation location) {
            return location(location.location);
        } else if (content instanceof TdApi.MessageLiveLocation location) {
            require(location.location);
            return location(location.location.location);
        } else if (content instanceof TdApi.MessageVenue venue) {
            return venue(venue.venue);
        } else if (content instanceof TdApi.MessageContact contact) {
            return contact(contact.contact);
        } else if (isExpired(content)) {
            return plain(EXPIRED_MEDIA);
        }
        return null;
    }

    private Content text(TdApi.MessageText messageText) {
        require(messageText.text);
        String body = format(messageText.text);
        if (messageText.linkPreview == null) {
            return new Content(body, List.of(), List.of());
        }
        return preview(body, messageText.linkPreview);
    }

    private Content preview(String body, TdApi.LinkPreview preview) {
        if (blank(preview.url)) {
            throw new InvalidContentException();
        }
        TelegramLinkPreviewImageSelector.SelectedImage image = TelegramLinkPreviewImageSelector.select(preview.type);
        List<SourceMedia> media = previewMedia(image);
        String imageSourceId = media.isEmpty() ? null : media.getFirst().sourceId();
        String description = preview.description == null ? null : preview.description.text;
        SourceLinkPreview normalized = new SourceLinkPreview(
            emptyToNull(preview.siteName), preview.url, emptyToNull(preview.title), emptyToNull(description), null,
            imageSourceId
        );
        return new Content(body, media, List.of(normalized));
    }

    private List<SourceMedia> previewMedia(TelegramLinkPreviewImageSelector.SelectedImage image) {
        if (image == null) {
            return List.of();
        }
        try {
            return List.of(sourceMedia(
                image.file(), image.mimeType(), null, "linkPreviewImage", image.remoteFileType(), 0
            ));
        } catch (InvalidContentException e) {
            return List.of();
        }
    }

    private String decorateBody(TdApi.Message message, String content) {
        var body = new StringBuilder();
        body.append(content);
        appendButtons(body, message.replyMarkup);
        if (message.factCheck != null && message.factCheck.text != null
            && !blank(message.factCheck.text.text)) {
            body.append("<hr><p><b>Fact check</b></p><blockquote>")
                .append(fragment(message.factCheck.text)).append("</blockquote>");
        }
        return body.toString();
    }

    private void appendButtons(StringBuilder body, TdApi.ReplyMarkup replyMarkup) {
        if (!(replyMarkup instanceof TdApi.ReplyMarkupInlineKeyboard keyboard) || keyboard.rows == null) {
            return;
        }
        for (TdApi.InlineKeyboardButton[] row : keyboard.rows) {
            if (row == null) {
                continue;
            }
            for (TdApi.InlineKeyboardButton button : row) {
                if (button != null && button.type instanceof TdApi.InlineKeyboardButtonTypeUrl url
                    && !blank(button.text) && safeUrl(url.url)) {
                    body.append("<p><a href=\"").append(escape(url.url)).append("\">")
                        .append(escape(button.text)).append("</a></p>");
                }
            }
        }
    }

    private Attribution attribution(TdApi.MessageForwardInfo forwardInfo) {
        if (forwardInfo == null) {
            return null;
        }
        TelegramForwardMetadataResolver.Source source = null;
        String name = null;
        String url = null;
        long originalMessageId = 0;
        if (forwardInfo.origin instanceof TdApi.MessageOriginHiddenUser hidden) {
            name = hidden.senderName;
        } else if (forwardInfo.origin instanceof TdApi.MessageOriginUser user) {
            source = metadataResolver.user(user.senderUserId);
        } else if (forwardInfo.origin instanceof TdApi.MessageOriginChat chat) {
            source = metadataResolver.chat(chat.senderChatId);
        } else if (forwardInfo.origin instanceof TdApi.MessageOriginChannel channel) {
            source = metadataResolver.chat(channel.chatId);
            originalMessageId = channel.messageId;
        }
        if (source != null) {
            name = source.name();
            if (!blank(source.username())) {
                url = "https://t.me/" + source.username();
                long publicMessageId = originalMessageId >> 20;
                if (publicMessageId > 0) {
                    url += "/" + publicMessageId;
                }
            }
        }
        if (blank(name) && forwardInfo.source != null) {
            name = forwardInfo.source.senderName;
        }
        return blank(name) ? null : new Attribution(name, url);
    }

    private boolean safeUrl(String value) {
        if (blank(value)) {
            return false;
        }
        try {
            URI uri = new URI(value);
            return uri.getScheme() != null && SAFE_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private Content media(TdApi.FormattedText caption, TdApi.File file, String mimeType, String fileName,
                          String fileType) {
        return new Content(formatNullable(caption), List.of(sourceMedia(file, mimeType, fileName, fileType, 0)),
            List.of());
    }

    private Content sticker(TdApi.Sticker sticker) {
        require(sticker);
        String mimeType;
        if (sticker.format instanceof TdApi.StickerFormatWebp) {
            mimeType = "image/webp";
        } else if (sticker.format instanceof TdApi.StickerFormatTgs) {
            mimeType = "application/x-tgsticker";
        } else if (sticker.format instanceof TdApi.StickerFormatWebm) {
            mimeType = "video/webm";
        } else {
            throw new InvalidContentException();
        }
        return new Content(formatPlain(sticker.emoji),
            List.of(sourceMedia(sticker.sticker, mimeType, null, "sticker", 0)), List.of());
    }

    private Content poll(TdApi.Poll poll) {
        require(poll);
        require(poll.question);
        require(poll.options);
        var body = new StringBuilder(format(poll.question));
        body.append("<ol>");
        for (TdApi.PollOption option : poll.options) {
            require(option);
            require(option.text);
            body.append("<li>").append(fragment(option.text)).append("</li>");
        }
        body.append("</ol>");
        if (poll.type instanceof TdApi.PollTypeQuiz quiz && quiz.correctOptionIds != null
            && quiz.correctOptionIds.length > 0) {
            body.append("<mr-spoiler-block title=\"Right answer\">");
            if (quiz.correctOptionIds.length == 1) {
                body.append("<p>").append(optionText(poll, quiz.correctOptionIds[0])).append("</p>");
            } else {
                body.append("<ul>");
                for (int optionId : quiz.correctOptionIds) {
                    body.append("<li>").append(optionText(poll, optionId)).append("</li>");
                }
                body.append("</ul>");
            }
            body.append("</mr-spoiler-block>");
        }
        return new Content(body.toString(), List.of(), List.of());
    }

    private String optionText(TdApi.Poll poll, int optionId) {
        if (optionId < 0 || optionId >= poll.options.length || poll.options[optionId] == null
            || poll.options[optionId].text == null) {
            throw new InvalidContentException();
        }
        return fragment(poll.options[optionId].text);
    }

    private Content checklist(TdApi.Checklist checklist) {
        require(checklist);
        require(checklist.title);
        require(checklist.tasks);
        var body = new StringBuilder(format(checklist.title));
        body.append("<ol>");
        for (TdApi.ChecklistTask task : checklist.tasks) {
            require(task);
            require(task.text);
            body.append("<li>").append(task.completedBy == null ? "☐ " : "☑ ")
                .append(fragment(task.text)).append("</li>");
        }
        body.append("</ol>");
        return new Content(body.toString(), List.of(), List.of());
    }

    private Content location(TdApi.Location location) {
        require(location);
        String coordinates = coordinates(location);
        String url = "https://www.openstreetmap.org/?mlat=" + decimal(location.latitude)
            + "&amp;mlon=" + decimal(location.longitude);
        return new Content("<p><a href=\"" + url + "\">" + coordinates + "</a></p>", List.of(), List.of());
    }

    private Content venue(TdApi.Venue venue) {
        require(venue);
        require(venue.location);
        String coordinates = coordinates(venue.location);
        String url = "https://www.openstreetmap.org/?mlat=" + decimal(venue.location.latitude)
            + "&amp;mlon=" + decimal(venue.location.longitude);
        String body = "<p>" + escape(venue.title) + "<br>" + escape(venue.address) + "<br><a href=\"" + url
            + "\">" + coordinates + "</a></p>";
        return new Content(body, List.of(), List.of());
    }

    private Content contact(TdApi.Contact contact) {
        require(contact);
        String name = ((contact.firstName == null ? "" : contact.firstName) + " "
            + (contact.lastName == null ? "" : contact.lastName)).trim();
        String phone = contact.phoneNumber == null ? "" : contact.phoneNumber;
        String href = phone.replaceAll("[^+0-9]", "");
        return new Content("<p>" + escape(name) + "<br><a href=\"tel:" + href + "\">" + escape(phone)
            + "</a></p>", List.of(), List.of());
    }

    private Content plain(String text) {
        if (text == null) {
            throw new InvalidContentException();
        }
        return new Content(formatPlain(text), List.of(), List.of());
    }

    private String formatPlain(String text) {
        return format(new TdApi.FormattedText(text, new TdApi.TextEntity[0]));
    }

    private String formatNullable(TdApi.FormattedText text) {
        return text == null ? "" : format(text);
    }

    private String fragment(TdApi.FormattedText text) {
        String html = format(text);
        if (html.startsWith("<p>") && html.endsWith("</p>") && html.indexOf("</p><p>") < 0) {
            return html.substring(3, html.length() - 4);
        }
        return html;
    }

    private String format(TdApi.FormattedText text) {
        try {
            return telegramHtmlFormatter.format(text);
        } catch (IllegalArgumentException e) {
            throw new InvalidContentException();
        }
    }

    private SourceMedia sourceMedia(TdApi.File file, String mimeType, String fileName, String fileType, int position) {
        return sourceMedia(file, mimeType, fileName, fileType, null, position);
    }

    private SourceMedia sourceMedia(
        TdApi.File file,
        String mimeType,
        String fileName,
        String fileType,
        String remoteFileType,
        int position
    ) {
        if (file == null || file.remote == null || blank(file.remote.uniqueId) && blank(file.remote.id)
            || blank(file.remote.id) || blank(mimeType)) {
            throw new InvalidContentException();
        }
        String sourceId = blank(file.remote.uniqueId) ? file.remote.id : file.remote.uniqueId;
        ObjectNode sourceData = JsonNodeFactory.instance.objectNode()
            .put("remoteId", file.remote.id)
            .put("fileType", fileType);
        if (!blank(remoteFileType)) {
            sourceData.put("remoteFileType", remoteFileType);
        }
        Long size = file.size > 0 ? file.size : null;
        return new SourceMedia(sourceId, position, mimeType, blank(fileName) ? null : fileName, size,
            TelegramCanonicalHash.media(file, mediaHashData(mimeType, fileName, fileType)), sourceData);
    }

    private ObjectNode mediaHashData(String mimeType, String fileName, String fileType) {
        ObjectNode data = JsonNodeFactory.instance.objectNode().put("mimeType", mimeType).put("fileType", fileType);
        if (!blank(fileName)) {
            data.put("fileName", fileName);
        }
        return data;
    }

    private TdApi.File largest(TdApi.Photo photo) {
        require(photo);
        if (photo.sizes == null || photo.sizes.length == 0) {
            throw new InvalidContentException();
        }
        return java.util.Arrays.stream(photo.sizes)
            .filter(Objects::nonNull)
            .filter(size -> size.photo != null)
            .max(Comparator.comparingLong(size -> (long) size.width * size.height))
            .orElseThrow(InvalidContentException::new)
            .photo;
    }

    private String coordinates(TdApi.Location location) {
        return decimal(location.latitude) + ", " + decimal(location.longitude);
    }

    private String decimal(double value) {
        return String.format(Locale.ROOT, "%s", value);
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private void require(Object value) {
        if (value == null) {
            throw new InvalidContentException();
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String emptyToNull(String value) {
        return blank(value) ? null : value;
    }

    private TelegramMappingResult skipped(TelegramSkipReason reason) {
        return new TelegramMappingResult.Skipped(reason);
    }

    private boolean isExpired(TdApi.MessageContent content) {
        return content instanceof TdApi.MessageExpiredPhoto
            || content instanceof TdApi.MessageExpiredVideo
            || content instanceof TdApi.MessageExpiredVideoNote
            || content instanceof TdApi.MessageExpiredVoiceNote;
    }

    private boolean isUnsupported(TdApi.MessageContent content) {
        return content instanceof TdApi.MessageDice
            || content instanceof TdApi.MessageStakeDice
            || content instanceof TdApi.MessageGame
            || content instanceof TdApi.MessageInvoice
            || content instanceof TdApi.MessagePaidMedia
            || content instanceof TdApi.MessageStory
            || content instanceof TdApi.MessageRichMessage
            || content instanceof TdApi.MessageUnsupported;
    }

    private boolean isService(TdApi.MessageContent content) {
        String name = content.getClass().getSimpleName();
        return content instanceof TdApi.MessagePinMessage
            || SERVICE_CONTENT_NAMES.contains(name)
            || name.startsWith("MessageChat")
            || name.startsWith("MessageVideoChat")
            || name.startsWith("MessageForumTopic")
            || name.startsWith("MessageBot")
            || name.startsWith("MessageWebApp")
            || name.startsWith("MessagePassport")
            || name.startsWith("MessageChecklistTask")
            || name.startsWith("MessageSuggestedPost");
    }

    private record Content(String body, List<SourceMedia> media, List<SourceLinkPreview> linkPreviews) {
    }

    private record Attribution(String name, String url) {
    }

    private static final class InvalidContentException extends RuntimeException {
    }

}
