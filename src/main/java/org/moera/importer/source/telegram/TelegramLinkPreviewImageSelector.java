package org.moera.importer.source.telegram;

import java.util.Comparator;
import java.util.Objects;

import it.tdlight.jni.TdApi;

final class TelegramLinkPreviewImageSelector {

    private TelegramLinkPreviewImageSelector() {
    }

    static SelectedImage select(TdApi.LinkPreviewType type) {
        if (type instanceof TdApi.LinkPreviewTypeAlbum album) {
            return album(album.media);
        } else if (type instanceof TdApi.LinkPreviewTypeAnimation animation) {
            return thumbnail(animation.animation == null ? null : animation.animation.thumbnail);
        } else if (type instanceof TdApi.LinkPreviewTypeApp app) {
            return photo(app.photo);
        } else if (type instanceof TdApi.LinkPreviewTypeArticle article) {
            return photo(article.photo);
        } else if (type instanceof TdApi.LinkPreviewTypeAudio audio) {
            return thumbnail(audio.audio == null ? null : audio.audio.albumCoverThumbnail);
        } else if (type instanceof TdApi.LinkPreviewTypeBackground background) {
            return document(background.document);
        } else if (type instanceof TdApi.LinkPreviewTypeChannelBoost boost) {
            return chatPhoto(boost.photo);
        } else if (type instanceof TdApi.LinkPreviewTypeChat chat) {
            return chatPhoto(chat.photo);
        } else if (type instanceof TdApi.LinkPreviewTypeDirectMessagesChat chat) {
            return chatPhoto(chat.photo);
        } else if (type instanceof TdApi.LinkPreviewTypeDocument document) {
            return document(document.document);
        } else if (type instanceof TdApi.LinkPreviewTypeEmbeddedAnimationPlayer animation) {
            return first(photo(animation.thumbnail),
                thumbnail(animation.animation == null ? null : animation.animation.thumbnail));
        } else if (type instanceof TdApi.LinkPreviewTypeEmbeddedAudioPlayer audio) {
            return first(photo(audio.thumbnail),
                thumbnail(audio.audio == null ? null : audio.audio.albumCoverThumbnail));
        } else if (type instanceof TdApi.LinkPreviewTypeEmbeddedVideoPlayer video) {
            return first(photo(video.thumbnail), video(video.video));
        } else if (type instanceof TdApi.LinkPreviewTypeGiftCollection collection) {
            return stickers(collection.icons);
        } else if (type instanceof TdApi.LinkPreviewTypePhoto photo) {
            return photo(photo.photo);
        } else if (type instanceof TdApi.LinkPreviewTypeSticker sticker) {
            return sticker(sticker.sticker);
        } else if (type instanceof TdApi.LinkPreviewTypeStickerSet stickerSet) {
            return stickers(stickerSet.stickers);
        } else if (type instanceof TdApi.LinkPreviewTypeStoryAlbum album) {
            return first(photo(album.photoIcon), video(album.videoIcon));
        } else if (type instanceof TdApi.LinkPreviewTypeSupergroupBoost boost) {
            return chatPhoto(boost.photo);
        } else if (type instanceof TdApi.LinkPreviewTypeTheme theme) {
            return documents(theme.documents);
        } else if (type instanceof TdApi.LinkPreviewTypeUser user) {
            return chatPhoto(user.photo);
        } else if (type instanceof TdApi.LinkPreviewTypeVideo video) {
            return first(photo(video.cover), video(video.video));
        } else if (type instanceof TdApi.LinkPreviewTypeVideoChat chat) {
            return chatPhoto(chat.photo);
        } else if (type instanceof TdApi.LinkPreviewTypeVideoNote videoNote) {
            return thumbnail(videoNote.videoNote == null ? null : videoNote.videoNote.thumbnail);
        } else if (type instanceof TdApi.LinkPreviewTypeWebApp webApp) {
            return photo(webApp.photo);
        }
        return null;
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
        if (thumbnail.format instanceof TdApi.ThumbnailFormatJpeg) {
            mimeType = "image/jpeg";
        } else if (thumbnail.format instanceof TdApi.ThumbnailFormatPng) {
            mimeType = "image/png";
        } else if (thumbnail.format instanceof TdApi.ThumbnailFormatWebp) {
            mimeType = "image/webp";
        } else if (thumbnail.format instanceof TdApi.ThumbnailFormatGif) {
            mimeType = "image/gif";
        } else {
            return null;
        }
        return new SelectedImage(thumbnail.file, mimeType);
    }

    private static SelectedImage video(TdApi.Video video) {
        return thumbnail(video == null ? null : video.thumbnail);
    }

    private static SelectedImage jpeg(TdApi.File file) {
        return file == null ? null : new SelectedImage(file, "image/jpeg");
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

    record SelectedImage(TdApi.File file, String mimeType) {
    }

}
