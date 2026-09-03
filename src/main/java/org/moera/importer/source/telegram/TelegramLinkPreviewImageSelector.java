package org.moera.importer.source.telegram;

import java.util.Comparator;
import java.util.Objects;

import it.tdlight.jni.TdApi;

final class TelegramLinkPreviewImageSelector {

    private TelegramLinkPreviewImageSelector() {
    }

    static SelectedImage select(TdApi.LinkPreviewType type) {
        return switch (type) {
            case TdApi.LinkPreviewTypeAlbum album -> album(album.media);
            case TdApi.LinkPreviewTypeAnimation animation ->
                thumbnail(animation.animation == null ? null : animation.animation.thumbnail);
            case TdApi.LinkPreviewTypeApp app -> photo(app.photo);
            case TdApi.LinkPreviewTypeArticle article -> photo(article.photo);
            case TdApi.LinkPreviewTypeAudio audio ->
                thumbnail(audio.audio == null ? null : audio.audio.albumCoverThumbnail);
            case TdApi.LinkPreviewTypeBackground background -> document(background.document);
            case TdApi.LinkPreviewTypeChannelBoost boost -> chatPhoto(boost.photo);
            case TdApi.LinkPreviewTypeChat chat -> chatPhoto(chat.photo);
            case TdApi.LinkPreviewTypeDirectMessagesChat chat -> chatPhoto(chat.photo);
            case TdApi.LinkPreviewTypeDocument document -> document(document.document);
            case TdApi.LinkPreviewTypeEmbeddedAnimationPlayer animation -> first(
                photo(animation.thumbnail),
                thumbnail(animation.animation == null ? null : animation.animation.thumbnail)
            );
            case TdApi.LinkPreviewTypeEmbeddedAudioPlayer audio -> first(
                photo(audio.thumbnail),
                thumbnail(audio.audio == null ? null : audio.audio.albumCoverThumbnail)
            );
            case TdApi.LinkPreviewTypeEmbeddedVideoPlayer video ->
                first(photo(video.thumbnail), video(video.video));
            case TdApi.LinkPreviewTypeGiftCollection collection -> stickers(collection.icons);
            case TdApi.LinkPreviewTypePhoto photo -> photo(photo.photo);
            case TdApi.LinkPreviewTypeSticker sticker -> sticker(sticker.sticker);
            case TdApi.LinkPreviewTypeStickerSet stickerSet -> stickers(stickerSet.stickers);
            case TdApi.LinkPreviewTypeStoryAlbum album -> first(photo(album.photoIcon), video(album.videoIcon));
            case TdApi.LinkPreviewTypeSupergroupBoost boost -> chatPhoto(boost.photo);
            case TdApi.LinkPreviewTypeTheme theme -> documents(theme.documents);
            case TdApi.LinkPreviewTypeUser user -> chatPhoto(user.photo);
            case TdApi.LinkPreviewTypeVideo video -> first(photo(video.cover), video(video.video));
            case TdApi.LinkPreviewTypeVideoChat chat -> chatPhoto(chat.photo);
            case TdApi.LinkPreviewTypeVideoNote videoNote ->
                thumbnail(videoNote.videoNote == null ? null : videoNote.videoNote.thumbnail);
            case TdApi.LinkPreviewTypeWebApp webApp -> photo(webApp.photo);
            case null, default -> null;
        };
    }

    private static SelectedImage album(TdApi.LinkPreviewAlbumMedia[] media) {
        if (media == null) {
            return null;
        }
        TdApi.PhotoSize largest = null;
        for (TdApi.LinkPreviewAlbumMedia item : media) {
            if (item instanceof TdApi.LinkPreviewAlbumMediaPhoto photo) {
                TdApi.PhotoSize candidate = largestSize(photo.photo == null ? null : photo.photo.sizes);
                if (larger(candidate, largest)) {
                    largest = candidate;
                }
            }
        }
        if (largest != null) {
            return jpeg(largest.photo);
        }
        for (TdApi.LinkPreviewAlbumMedia item : media) {
            if (item instanceof TdApi.LinkPreviewAlbumMediaVideo video) {
                SelectedImage selected = video(video.video);
                if (selected != null) {
                    return selected;
                }
            }
        }
        return null;
    }

    private static SelectedImage chatPhoto(TdApi.ChatPhoto photo) {
        return photo == null ? null : jpeg(largestFile(photo.sizes));
    }

    private static SelectedImage document(TdApi.Document document) {
        return thumbnail(document == null ? null : document.thumbnail);
    }

    private static SelectedImage documents(TdApi.Document[] documents) {
        if (documents == null) {
            return null;
        }
        for (TdApi.Document document : documents) {
            SelectedImage selected = document(document);
            if (selected != null) {
                return selected;
            }
        }
        return null;
    }

    private static SelectedImage first(SelectedImage preferred, SelectedImage fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static SelectedImage photo(TdApi.Photo photo) {
        return photo == null ? null : jpeg(largestFile(photo.sizes));
    }

    private static SelectedImage sticker(TdApi.Sticker sticker) {
        return thumbnail(sticker == null ? null : sticker.thumbnail);
    }

    private static SelectedImage stickers(TdApi.Sticker[] stickers) {
        if (stickers == null) {
            return null;
        }
        for (TdApi.Sticker sticker : stickers) {
            SelectedImage selected = sticker(sticker);
            if (selected != null) {
                return selected;
            }
        }
        return null;
    }

    private static SelectedImage thumbnail(TdApi.Thumbnail thumbnail) {
        if (thumbnail == null || thumbnail.file == null) {
            return null;
        }
        String mimeType;
        switch (thumbnail.format) {
            case TdApi.ThumbnailFormatJpeg _ -> mimeType = "image/jpeg";
            case TdApi.ThumbnailFormatPng _ -> mimeType = "image/png";
            case TdApi.ThumbnailFormatWebp _ -> mimeType = "image/webp";
            case TdApi.ThumbnailFormatGif _ -> mimeType = "image/gif";
            case null, default -> {
                return null;
            }
        }
        return new SelectedImage(thumbnail.file, mimeType, "thumbnail");
    }

    private static SelectedImage video(TdApi.Video video) {
        return thumbnail(video == null ? null : video.thumbnail);
    }

    private static SelectedImage jpeg(TdApi.File file) {
        return file == null ? null : new SelectedImage(file, "image/jpeg", "photo");
    }

    private static TdApi.File largestFile(TdApi.PhotoSize[] sizes) {
        TdApi.PhotoSize largest = largestSize(sizes);
        return largest == null ? null : largest.photo;
    }

    private static TdApi.PhotoSize largestSize(TdApi.PhotoSize[] sizes) {
        if (sizes == null) {
            return null;
        }
        return java.util.Arrays.stream(sizes)
            .filter(Objects::nonNull)
            .filter(size -> size.photo != null)
            .max(Comparator.comparingLong(TelegramLinkPreviewImageSelector::area))
            .orElse(null);
    }

    private static boolean larger(TdApi.PhotoSize candidate, TdApi.PhotoSize current) {
        return candidate != null && (current == null || area(candidate) > area(current));
    }

    private static long area(TdApi.PhotoSize size) {
        return (long) size.width * size.height;
    }

    record SelectedImage(TdApi.File file, String mimeType, String remoteFileType) {
    }

}
