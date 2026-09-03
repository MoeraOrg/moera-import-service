package org.moera.importer.source.telegram;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.moera.importer.model.BodyFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramMessageMapperTest {

    private final TelegramMessageMapper telegramMessageMapper = new TelegramMessageMapper();

    @Test
    void mapsTextToUniversalMessage() {
        TdApi.Message telegram = message(new TdApi.MessageText(
            new TdApi.FormattedText("Hello <Moera>", new TdApi.TextEntity[0]),
            null,
            null
        ));
        telegram.id = 9_223_372_036_854L;
        telegram.date = 1_700_000_000;
        telegram.isPinned = true;

        var result = (TelegramMappingResult.Mapped) telegramMessageMapper.map(telegram);

        assertThat(result.message().sourceId()).isEqualTo("9223372036854");
        assertThat(result.message().publishedAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_000));
        assertThat(result.message().pinned()).isTrue();
        assertThat(result.message().deleted()).isFalse();
        assertThat(result.message().body()).isEqualTo("<p>Hello &lt;Moera&gt;</p>");
        assertThat(result.message().bodyFormat()).isEqualTo(BodyFormat.HTML);
        assertThat(result.message().sourceBodyHash()).containsExactly(TelegramCanonicalHash.body(telegram));
        assertThat(result.message().media()).isEmpty();
        assertThat(result.message().linkPreviews()).isEmpty();
    }

    @Test
    void returnsInvalidContentForMalformedSupportedMessage() {
        var result = telegramMessageMapper.map(message(new TdApi.MessageText(null, null, null)));

        assertThat(result).isEqualTo(
            new TelegramMappingResult.Skipped(TelegramSkipReason.INVALID_CONTENT)
        );
    }

    @Test
    void returnsInvalidContentForMalformedTextEntities() {
        TdApi.FormattedText malformed = new TdApi.FormattedText("text", new TdApi.TextEntity[] {null});

        assertThat(telegramMessageMapper.map(message(new TdApi.MessageText(malformed, null, null))))
            .isEqualTo(new TelegramMappingResult.Skipped(TelegramSkipReason.INVALID_CONTENT));
    }

    @Test
    void mapsLargestPhotoAndCaptionWithoutTechnicalMetadata() {
        TdApi.MessagePhoto content = new TdApi.MessagePhoto();
        content.photo = new TdApi.Photo(false, null, new TdApi.PhotoSize[] {
            new TdApi.PhotoSize("m", file("small", "small-remote", 100), 320, 240, new int[0]),
            new TdApi.PhotoSize("x", file("large", "large-remote", 200), 1280, 720, new int[0])
        });
        content.caption = text("Caption");

        var mapped = mapped(message(content));

        assertThat(mapped.body()).isEqualTo("<p>Caption</p>");
        assertThat(mapped.media()).hasSize(1);
        assertThat(mapped.media().getFirst().sourceId()).isEqualTo("large");
        assertThat(mapped.media().getFirst().mimeType()).isEqualTo("image/jpeg");
        assertThat(mapped.media().getFirst().size()).isEqualTo(200);
        assertThat(mapped.media().getFirst().sourceData()).isEqualTo(
            JsonNodeFactory.instance.objectNode().put("remoteId", "large-remote").put("fileType", "photo")
        );
    }

    @Test
    void mapsPrimaryFilesForOrdinaryMedia() {
        TdApi.MessageAnimation animation = new TdApi.MessageAnimation();
        animation.animation = new TdApi.Animation();
        animation.animation.animation = file("animation", "animation-remote", 11);
        animation.animation.mimeType = "image/gif";
        animation.animation.fileName = "a.gif";
        animation.caption = text("animation");

        TdApi.MessageAudio audio = new TdApi.MessageAudio();
        audio.audio = new TdApi.Audio();
        audio.audio.audio = file("audio", "audio-remote", 12);
        audio.audio.mimeType = "audio/mpeg";
        audio.audio.fileName = "a.mp3";
        audio.caption = text("audio");

        TdApi.MessageDocument document = new TdApi.MessageDocument();
        document.document = new TdApi.Document();
        document.document.document = file("document", "document-remote", 13);
        document.document.mimeType = "application/pdf";
        document.document.fileName = "a.pdf";
        document.caption = text("document");

        TdApi.MessageVideo video = new TdApi.MessageVideo();
        video.video = new TdApi.Video();
        video.video.video = file("video", "video-remote", 14);
        video.video.mimeType = "video/mp4";
        video.video.fileName = "a.mp4";
        video.caption = text("video");
        video.alternativeVideos = new TdApi.AlternativeVideo[0];

        TdApi.MessageVideoNote videoNote = new TdApi.MessageVideoNote();
        videoNote.videoNote = new TdApi.VideoNote();
        videoNote.videoNote.video = file("video-note", "video-note-remote", 15);

        TdApi.MessageVoiceNote voiceNote = new TdApi.MessageVoiceNote();
        voiceNote.voiceNote = new TdApi.VoiceNote();
        voiceNote.voiceNote.voice = file("voice", "voice-remote", 16);
        voiceNote.voiceNote.mimeType = "audio/ogg";
        voiceNote.caption = text("voice");

        assertMedia(animation, "animation", "image/gif", "a.gif", "<p>animation</p>");
        assertMedia(audio, "audio", "audio/mpeg", "a.mp3", "<p>audio</p>");
        assertMedia(document, "document", "application/pdf", "a.pdf", "<p>document</p>");
        assertMedia(video, "video", "video/mp4", "a.mp4", "<p>video</p>");
        assertMedia(videoNote, "video-note", "video/mp4", null, "");
        assertMedia(voiceNote, "voice", "audio/ogg", null, "<p>voice</p>");
    }

    @Test
    void mapsPollQuizAndChecklistSemantics() {
        TdApi.Poll poll = new TdApi.Poll();
        poll.question = text("Best language?");
        poll.options = new TdApi.PollOption[] {option("Java"), option("Kotlin"), option("Both")};
        poll.type = new TdApi.PollTypeQuiz(new int[] {0, 2}, text("ignored"), null);
        TdApi.MessagePoll pollMessage = new TdApi.MessagePoll(poll, text("ignored"), null, true);

        var quiz = mapped(message(pollMessage));

        assertThat(quiz.body()).isEqualTo("<p>Best language?</p><ol><li>Java</li><li>Kotlin</li><li>Both</li></ol>"
            + "<mr-spoiler-block title=\"Right answer\"><ul><li>Java</li><li>Both</li></ul></mr-spoiler-block>");

        TdApi.ChecklistTask open = new TdApi.ChecklistTask(1, text("Write tests"), null, 0);
        TdApi.ChecklistTask done = new TdApi.ChecklistTask(2, text("Implement"), new TdApi.MessageSenderUser(7), 123);
        TdApi.Checklist checklist = new TdApi.Checklist(text("Plan"), new TdApi.ChecklistTask[] {open, done},
            true, false, true, false);

        assertThat(mapped(message(new TdApi.MessageChecklist(checklist))).body())
            .isEqualTo("<p>Plan</p><ol><li>☐ Write tests</li><li>☑ Implement</li></ol>");
    }

    @Test
    void mapsLocationVenueContactStickerAnimatedEmojiAndExpiredMedia() {
        var location = mapped(message(new TdApi.MessageLocation(new TdApi.Location(32.1, 34.8, 100))));
        assertThat(location.body()).isEqualTo(
            "<p><a href=\"https://www.openstreetmap.org/?mlat=32.1&amp;mlon=34.8\">32.1, 34.8</a></p>"
        );

        var venue = mapped(message(new TdApi.MessageVenue(
            new TdApi.Venue(new TdApi.Location(32.1, 34.8, 0), "Cafe", "Main St", "x", "id", "type")
        )));
        assertThat(venue.body()).isEqualTo("<p>Cafe<br>Main St<br>"
            + "<a href=\"https://www.openstreetmap.org/?mlat=32.1&amp;mlon=34.8\">32.1, 34.8</a></p>");

        var contact = mapped(message(new TdApi.MessageContact(new TdApi.Contact(
            "+1 555", "Jane", "Doe", "ignored", 77
        ))));
        assertThat(contact.body()).isEqualTo("<p>Jane Doe<br><a href=\"tel:+1555\">+1 555</a></p>");

        TdApi.Sticker sticker = new TdApi.Sticker();
        sticker.emoji = "🙂";
        sticker.format = new TdApi.StickerFormatWebp();
        sticker.sticker = file("sticker", "sticker-remote", 20);
        var stickerMessage = mapped(message(new TdApi.MessageSticker(sticker, false)));
        assertThat(stickerMessage.body()).isEqualTo("<p>🙂</p>");
        assertThat(stickerMessage.media()).hasSize(1);
        assertThat(stickerMessage.media().getFirst().mimeType()).isEqualTo("image/webp");

        var animated = mapped(message(new TdApi.MessageAnimatedEmoji(new TdApi.AnimatedEmoji(), "❤️")));
        assertThat(animated.body()).isEqualTo("<p>❤️</p>");
        assertThat(animated.media()).isEmpty();

        for (TdApi.MessageContent expired : List.of(new TdApi.MessageExpiredPhoto(), new TdApi.MessageExpiredVideo(),
            new TdApi.MessageExpiredVideoNote(), new TdApi.MessageExpiredVoiceNote())) {
            var mapped = mapped(message(expired));
            assertThat(mapped.body()).isEqualTo("<p>[Media is no longer available]</p>");
            assertThat(mapped.media()).isEmpty();
        }
    }

    @Test
    void decoratesBodyWithForwardAttributionButtonsAndFactCheckAndIgnoresAuthorSignature() {
        TdApi.Message telegram = message(new TdApi.MessageText(text("Content"), null, null));
        telegram.forwardInfo = new TdApi.MessageForwardInfo(new TdApi.MessageOriginHiddenUser("Source <name>"),
            0, null, "ignored");
        telegram.authorSignature = "Editor & Co";
        telegram.replyMarkup = new TdApi.ReplyMarkupInlineKeyboard(new TdApi.InlineKeyboardButton[][] {{
            new TdApi.InlineKeyboardButton("Open", 0, null,
                new TdApi.InlineKeyboardButtonTypeUrl("https://example.org/a?x=1&y=2")),
            new TdApi.InlineKeyboardButton("Ignore", 0, null, new TdApi.InlineKeyboardButtonTypeCallback(new byte[0])),
            new TdApi.InlineKeyboardButton("Unsafe", 0, null,
                new TdApi.InlineKeyboardButtonTypeUrl("javascript:alert(1)"))
        }});
        telegram.factCheck = new TdApi.FactCheck(text("Verified <fact>"), "US");

        var mapped = mapped(telegram);

        assertThat(mapped.body()).isEqualTo("<p>Source &lt;name&gt;</p><p>Content</p>"
            + "<p><a href=\"https://example.org/a?x=1&amp;y=2\">Open</a></p>"
            + "<hr><p><b>Fact check</b></p><blockquote>Verified &lt;fact&gt;</blockquote>");
        assertThat(mapped.sourceData().path("forwardAttribution").path("name").asText())
            .isEqualTo("Source <name>");
    }

    @Test
    void resolvesPublicChannelForwardToExactPost() {
        TelegramMessageMapper mapper = new TelegramMessageMapper(new TelegramForwardMetadataResolver() {

            @Override
            public Source user(long userId) {
                return null;
            }

            @Override
            public Source chat(long chatId) {
                return new Source("Moera News", "moera_news");
            }

        });
        long tdlibMessageId = 6660L << 20;
        TdApi.Message telegram = message(new TdApi.MessageText(text("News"), null, null));
        telegram.forwardInfo = new TdApi.MessageForwardInfo(
            new TdApi.MessageOriginChannel(-1001, tdlibMessageId, ""), 0, null, ""
        );

        var mapped = ((TelegramMappingResult.Mapped) mapper.map(telegram)).message();

        assertThat(mapped.body()).startsWith(
            "<p><a href=\"https://t.me/moera_news/6660\">Moera News</a></p>"
        );
        assertThat(mapped.sourceData().path("forwardAttribution").path("url").asText())
            .isEqualTo("https://t.me/moera_news/6660");
    }

    @Test
    void mapsLinkPreviewAndAttachesItsLargestPhoto() {
        TdApi.Photo photo = new TdApi.Photo(false, null, new TdApi.PhotoSize[] {
            new TdApi.PhotoSize("m", file("preview-small", "small-id", 30), 100, 100, new int[0]),
            new TdApi.PhotoSize("x", file("preview-large", "large-id", 40), 800, 600, new int[0])
        });
        TdApi.LinkPreview preview = new TdApi.LinkPreview(
            "https://example.org/article", "example.org/article", "Example", "Title",
            text("Description"), "Author", new TdApi.LinkPreviewTypeArticle(photo),
            true, true, true, true, true, 5
        );
        TdApi.MessageText content = new TdApi.MessageText(text("Read it"), preview, new TdApi.LinkPreviewOptions());

        var mapped = mapped(message(content));

        assertThat(mapped.linkPreviews()).hasSize(1);
        assertThat(mapped.linkPreviews().getFirst().siteName()).isEqualTo("Example");
        assertThat(mapped.linkPreviews().getFirst().url()).isEqualTo("https://example.org/article");
        assertThat(mapped.linkPreviews().getFirst().title()).isEqualTo("Title");
        assertThat(mapped.linkPreviews().getFirst().description()).isEqualTo("Description");
        assertThat(mapped.linkPreviews().getFirst().publishedAt()).isNull();
        assertThat(mapped.linkPreviews().getFirst().imageSourceMediaId()).isEqualTo("preview-large");
        assertThat(mapped.media()).extracting(org.moera.importer.source.SourceMedia::sourceId)
            .containsExactly("preview-large");
        assertThat(mapped.media().getFirst().sourceData().path("fileType").asText())
            .isEqualTo("linkPreviewImage");
        assertThat(mapped.media().getFirst().sourceData().path("remoteFileType").asText()).isEqualTo("photo");
    }

    @Test
    void keepsTextualPreviewWhenSelectedImageCannotBeImported() {
        TdApi.Photo photo = new TdApi.Photo(false, null, new TdApi.PhotoSize[] {
            new TdApi.PhotoSize("x", new TdApi.File(), 800, 600, new int[0])
        });
        TdApi.LinkPreview preview = new TdApi.LinkPreview(
            "https://example.org/article", "example.org/article", "Example", "Title",
            text("Description"), "", new TdApi.LinkPreviewTypeArticle(photo),
            false, false, false, false, false, 0
        );

        var mapped = mapped(message(new TdApi.MessageText(text("Read"), preview, null)));

        assertThat(mapped.linkPreviews()).hasSize(1);
        assertThat(mapped.linkPreviews().getFirst().imageSourceMediaId()).isNull();
        assertThat(mapped.media()).isEmpty();
    }

    @Test
    void preservesSelectedPreviewThumbnailMimeType() {
        TdApi.Document document = new TdApi.Document();
        document.thumbnail = new TdApi.Thumbnail(new TdApi.ThumbnailFormatPng(), 100, 100,
            file("preview-png", "preview-png-id", 25));
        TdApi.LinkPreview preview = new TdApi.LinkPreview(
            "https://example.org", "example.org", "Example", "Title", null, "",
            new TdApi.LinkPreviewTypeDocument(document), false, false, false, false, false, 0
        );

        var mapped = mapped(message(new TdApi.MessageText(text("Read"), preview, null)));

        assertThat(mapped.media().getFirst().mimeType()).isEqualTo("image/png");
        assertThat(mapped.media().getFirst().sourceData().path("fileType").asText())
            .isEqualTo("linkPreviewImage");
        assertThat(mapped.media().getFirst().sourceData().path("remoteFileType").asText()).isEqualTo("thumbnail");
    }

    @ParameterizedTest
    @MethodSource("additionalStaticPreviewImages")
    void mapsOnlyStaticImagesFromAdditionalPreviewTypes(TdApi.LinkPreviewType type, String expectedSourceId,
                                                         String expectedMimeType) {
        TdApi.LinkPreview preview = new TdApi.LinkPreview(
            "https://example.org", "example.org", "Example", "Title", null, "", type,
            false, false, false, false, false, 0
        );

        var mapped = mapped(message(new TdApi.MessageText(text("Read"), preview, null)));

        assertThat(mapped.media()).extracting(org.moera.importer.source.SourceMedia::sourceId)
            .containsExactly(expectedSourceId);
        assertThat(mapped.media().getFirst().mimeType()).isEqualTo(expectedMimeType);
        assertThat(mapped.linkPreviews().getFirst().imageSourceMediaId()).isEqualTo(expectedSourceId);
    }

    @ParameterizedTest
    @MethodSource("unsupportedContent")
    void skipsUnsupportedUserContent(TdApi.MessageContent content) {
        assertThat(telegramMessageMapper.map(message(content))).isEqualTo(
            new TelegramMappingResult.Skipped(TelegramSkipReason.UNSUPPORTED_CONTENT)
        );
    }

    @ParameterizedTest
    @MethodSource("serviceContent")
    void skipsServiceMessages(TdApi.MessageContent content) {
        assertThat(telegramMessageMapper.map(message(content))).isEqualTo(
            new TelegramMappingResult.Skipped(TelegramSkipReason.SERVICE_MESSAGE)
        );
    }

    @Test
    void doesNotTurnProgrammingErrorsIntoSkips() {
        assertThatThrownBy(() -> telegramMessageMapper.map(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void propagatesProgrammingErrorsFromForwardMetadataResolver() {
        TelegramMessageMapper mapper = new TelegramMessageMapper(new TelegramForwardMetadataResolver() {

            @Override
            public Source user(long userId) {
                throw new IllegalArgumentException("resolver bug");
            }

            @Override
            public Source chat(long chatId) {
                return null;
            }

        });
        TdApi.Message telegram = message(new TdApi.MessageText(text("Forwarded"), null, null));
        telegram.forwardInfo = new TdApi.MessageForwardInfo(new TdApi.MessageOriginUser(7), 0, null, "");

        assertThatThrownBy(() -> mapper.map(telegram))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("resolver bug");
    }

    private static Stream<TdApi.MessageContent> unsupportedContent() {
        return Stream.of(
            new TdApi.MessageDice(),
            new TdApi.MessageStakeDice(),
            new TdApi.MessageGame(),
            new TdApi.MessageInvoice(),
            new TdApi.MessagePaidMedia(),
            new TdApi.MessageStory(),
            new TdApi.MessageGiveaway(),
            new TdApi.MessageGiveawayCreated(),
            new TdApi.MessageGiveawayCompleted(),
            new TdApi.MessageGiveawayWinners(),
            new TdApi.MessageGift(),
            new TdApi.MessageGiftedPremium(),
            new TdApi.MessageGiftedStars(),
            new TdApi.MessageGiftedTon(),
            new TdApi.MessagePremiumGiftCode(),
            new TdApi.MessageUpgradedGift(),
            new TdApi.MessageUpgradedGiftPurchaseOffer(),
            new TdApi.MessageUpgradedGiftPurchaseOfferRejected(),
            new TdApi.MessageRefundedUpgradedGift(),
            new TdApi.MessageRichMessage(),
            new TdApi.MessageUnsupported()
        );
    }

    private static Stream<TdApi.MessageContent> serviceContent() {
        return Stream.of(
            new TdApi.MessagePinMessage(),
            new TdApi.MessageChatChangeTitle(),
            new TdApi.MessageChatAddMembers(),
            new TdApi.MessageVideoChatStarted(),
            new TdApi.MessageBasicGroupChatCreate(),
            new TdApi.MessageCall(),
            new TdApi.MessageContactRegistered(),
            new TdApi.MessageCustomServiceAction(),
            new TdApi.MessageDirectMessagePriceChanged(),
            new TdApi.MessageGameScore(),
            new TdApi.MessageGroupCall(),
            new TdApi.MessageInviteVideoChatParticipants(),
            new TdApi.MessageManagedBotCreated(),
            new TdApi.MessagePaidMessagePriceChanged(),
            new TdApi.MessagePaidMessagesRefunded(),
            new TdApi.MessagePaymentRefunded(),
            new TdApi.MessagePaymentSuccessful(),
            new TdApi.MessagePollOptionAdded(),
            new TdApi.MessagePollOptionDeleted(),
            new TdApi.MessageProximityAlertTriggered(),
            new TdApi.MessageScreenshotTaken(),
            new TdApi.MessageSuggestBirthdate(),
            new TdApi.MessageSuggestProfilePhoto(),
            new TdApi.MessageSupergroupChatCreate(),
            new TdApi.MessageUsersShared()
        );
    }

    private static Stream<Arguments> additionalStaticPreviewImages() {
        TdApi.VideoNote videoNote = new TdApi.VideoNote();
        videoNote.video = file("ignored-video-note", "ignored-video-note-id", 100);
        videoNote.thumbnail = thumbnail(new TdApi.ThumbnailFormatJpeg(), "video-note-thumbnail");

        TdApi.Sticker sticker = new TdApi.Sticker();
        sticker.sticker = file("ignored-sticker", "ignored-sticker-id", 100);
        sticker.thumbnail = thumbnail(new TdApi.ThumbnailFormatWebp(), "sticker-thumbnail");

        TdApi.Document background = new TdApi.Document();
        background.document = file("ignored-background", "ignored-background-id", 100);
        background.thumbnail = thumbnail(new TdApi.ThumbnailFormatPng(), "background-thumbnail");

        TdApi.ChatPhoto chatPhoto = new TdApi.ChatPhoto();
        chatPhoto.sizes = new TdApi.PhotoSize[] {
            new TdApi.PhotoSize("m", file("chat-small", "chat-small-id", 10), 100, 100, new int[0]),
            new TdApi.PhotoSize("x", file("chat-large", "chat-large-id", 20), 800, 600, new int[0])
        };

        TdApi.Video albumVideo = new TdApi.Video();
        albumVideo.video = file("ignored-album-video", "ignored-album-video-id", 100);
        albumVideo.thumbnail = thumbnail(new TdApi.ThumbnailFormatJpeg(), "album-video-thumbnail");
        TdApi.LinkPreviewAlbumMediaVideo albumVideoMedia = new TdApi.LinkPreviewAlbumMediaVideo(albumVideo);
        TdApi.LinkPreviewAlbumMediaPhoto albumPhotoMedia = new TdApi.LinkPreviewAlbumMediaPhoto(photo(
            "album-photo", 1024, 768
        ));

        return Stream.of(
            Arguments.of(new TdApi.LinkPreviewTypeVideoNote(videoNote), "video-note-thumbnail", "image/jpeg"),
            Arguments.of(new TdApi.LinkPreviewTypeSticker(sticker), "sticker-thumbnail", "image/webp"),
            Arguments.of(new TdApi.LinkPreviewTypeBackground(background, null), "background-thumbnail", "image/png"),
            Arguments.of(new TdApi.LinkPreviewTypeChat(null, chatPhoto, false), "chat-large", "image/jpeg"),
            Arguments.of(new TdApi.LinkPreviewTypeAlbum(new TdApi.LinkPreviewAlbumMedia[] {
                albumVideoMedia, albumPhotoMedia
            }, ""), "album-photo", "image/jpeg")
        );
    }

    private TdApi.Message message(TdApi.MessageContent content) {
        TdApi.Message message = new TdApi.Message();
        message.id = 42;
        message.date = 1_700_000_000;
        message.authorSignature = "";
        message.content = content;
        return message;
    }

    @Test
    void storesMediaAlbumIdForGrouping() {
        TdApi.Message message = message(new TdApi.MessageText(text("album"), null, null));
        message.mediaAlbumId = 987654321;

        assertThat(mapped(message).sourceData().path("mediaAlbumId").asLong()).isEqualTo(987654321);
    }

    private org.moera.importer.source.SourceMessage mapped(TdApi.Message message) {
        return ((TelegramMappingResult.Mapped) telegramMessageMapper.map(message)).message();
    }

    private void assertMedia(TdApi.MessageContent content, String sourceId, String mimeType, String fileName,
                             String body) {
        var mapped = mapped(message(content));
        assertThat(mapped.body()).isEqualTo(body);
        assertThat(mapped.media()).hasSize(1);
        assertThat(mapped.media().getFirst().sourceId()).isEqualTo(sourceId);
        assertThat(mapped.media().getFirst().mimeType()).isEqualTo(mimeType);
        assertThat(mapped.media().getFirst().fileName()).isEqualTo(fileName);
    }

    private static TdApi.FormattedText text(String value) {
        return new TdApi.FormattedText(value, new TdApi.TextEntity[0]);
    }

    private static TdApi.PollOption option(String value) {
        TdApi.PollOption option = new TdApi.PollOption();
        option.text = text(value);
        return option;
    }

    private static TdApi.Photo photo(String sourceId, int width, int height) {
        return new TdApi.Photo(false, null, new TdApi.PhotoSize[] {
            new TdApi.PhotoSize("x", file(sourceId, sourceId + "-id", 25), width, height, new int[0])
        });
    }

    private static TdApi.Thumbnail thumbnail(TdApi.ThumbnailFormat format, String sourceId) {
        return new TdApi.Thumbnail(format, 100, 100, file(sourceId, sourceId + "-id", 25));
    }

    private static TdApi.File file(String uniqueId, String remoteId, long size) {
        return new TdApi.File(1, size, size + 1, null, new TdApi.RemoteFile(remoteId, uniqueId, false, true, size));
    }

}
