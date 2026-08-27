package org.moera.importer.moera;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.inject.Inject;

import org.moera.importer.application.PublicationPayload;
import org.moera.importer.model.Destination;
import org.moera.importer.model.Media;
import org.moera.lib.node.MoeraNode;
import org.moera.lib.node.exception.MoeraNodeApiException;
import org.moera.lib.node.exception.MoeraNodeApiNotFoundException;
import org.moera.lib.node.exception.MoeraNodeException;
import org.moera.lib.node.types.Features;
import org.moera.lib.node.types.FeedReference;
import org.moera.lib.node.types.PostingText;
import org.moera.lib.node.types.PrivateMediaFileInfo;
import org.moera.lib.node.types.SettingInfo;
import org.moera.lib.node.types.StoryAttributes;
import org.springframework.stereotype.Service;

@Service
public class MoeraService {

    private static final Duration CAPABILITIES_TTL = Duration.ofMinutes(5);
    private static final String MEDIA_MAX_SIZE = "media.max-size";
    private static final Set<String> TEMPORARY_ERROR_CODES = Set.of(
        "media.download-failed",
        "media.storage-error",
        "media-upload.not-completed"
    );

    @Inject
    private Clock clock;

    @Inject
    private MoeraClientFactory moeraClientFactory;

    private final Map<Long, CachedCapabilities> capabilitiesCache = new ConcurrentHashMap<>();

    public DestinationCapabilities capabilities(Destination destination) {
        Objects.requireNonNull(destination, "destination");
        Instant now = clock.instant();
        return capabilitiesCache.compute(destination.id(), (id, cached) -> {
            if (cached != null && cached.expiresAt().isAfter(now)) {
                return cached;
            }
            DestinationCapabilities capabilities = loadCapabilities(destination);
            return new CachedCapabilities(capabilities, now.plus(CAPABILITIES_TTL));
        }).capabilities();
    }

    public MediaUpload uploadMedia(Destination destination, Path file, Media media) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(media, "media");
        MoeraNode moeraNode = moeraClientFactory.create(destination);
        try {
            PrivateMediaFileInfo info = moeraNode.uploadPrivateMedia(
                null, null, true, file, media.mimeType()
            );
            return classifyMedia(moeraNode, info);
        } catch (MoeraNodeException e) {
            throw externalFailure("Cannot upload media to Moera", e);
        }
    }

    public MediaUpload checkMediaCompression(Destination destination, String originalMediaId) {
        MoeraNode moeraNode = moeraClientFactory.create(destination);
        try {
            PrivateMediaFileInfo original = moeraNode.getPrivateMediaInfo(originalMediaId, null);
            return classifyMedia(moeraNode, original);
        } catch (MoeraNodeException e) {
            throw externalFailure("Cannot check Moera media compression", e);
        }
    }

    public CreatedPosting createPost(Destination destination, PublicationPayload payload) {
        MoeraNode moeraNode = moeraClientFactory.create(destination);
        try {
            var created = moeraNode.createPosting(posting(payload, true));
            Map<String, String> storyIds = new LinkedHashMap<>();
            if (created.getFeedReferences() != null) {
                for (FeedReference reference : created.getFeedReferences()) {
                    if (reference.getFeedName() != null && reference.getStoryId() != null) {
                        storyIds.putIfAbsent(reference.getFeedName(), reference.getStoryId());
                    }
                }
            }
            return new CreatedPosting(created.getId(), storyIds);
        } catch (MoeraNodeException e) {
            throw mutatingFailure("Moera posting creation outcome is unknown", e);
        }
    }

    public void updatePost(Destination destination, String postingId, PublicationPayload payload) {
        MoeraNode moeraNode = moeraClientFactory.create(destination);
        try {
            moeraNode.updatePosting(postingId, posting(payload, false));
        } catch (MoeraNodeException e) {
            throw mutatingFailure("Moera posting update outcome is unknown", e);
        }
    }

    public void updateStory(Destination destination, String storyId, boolean pinned) {
        MoeraNode moeraNode = moeraClientFactory.create(destination);
        StoryAttributes attributes = new StoryAttributes();
        attributes.setPinned(pinned);
        try {
            moeraNode.updateStory(storyId, attributes);
        } catch (MoeraNodeException e) {
            throw mutatingFailure("Moera story update outcome is unknown", e);
        }
    }

    public void deletePost(Destination destination, String postingId) {
        MoeraNode moeraNode = moeraClientFactory.create(destination);
        try {
            moeraNode.deletePosting(postingId);
        } catch (MoeraNodeApiNotFoundException e) {
            // Already deleted.
        } catch (MoeraNodeException e) {
            throw mutatingFailure("Moera posting deletion outcome is unknown", e);
        }
    }

    private DestinationCapabilities loadCapabilities(Destination destination) {
        MoeraNode moeraNode = moeraClientFactory.create(destination);
        try {
            Features features = moeraNode.getFeatures();
            SettingInfo[] settings = moeraNode.getNodeSettings(MEDIA_MAX_SIZE);
            long mediaMaxSize = mediaMaxSize(settings);
            if (features == null || features.getPosting() == null
                || features.getPosting().getSourceFormats() == null) {
                throw new MoeraConfigurationException("Moera posting capabilities are incomplete");
            }
            return new DestinationCapabilities(mediaMaxSize, features.getPosting().getSourceFormats());
        } catch (MoeraNodeException e) {
            throw externalFailure("Cannot load Moera capabilities", e);
        }
    }

    private long mediaMaxSize(SettingInfo[] settings) {
        if (settings != null) {
            for (SettingInfo setting : settings) {
                if (MEDIA_MAX_SIZE.equals(setting.getName())) {
                    try {
                        long value = Long.parseLong(setting.getValue());
                        if (value > 0) {
                            return value;
                        }
                    } catch (NumberFormatException e) {
                        throw new MoeraConfigurationException("Moera media.max-size setting is invalid", e);
                    }
                }
            }
        }
        throw new MoeraConfigurationException("Moera media.max-size setting is unavailable");
    }

    private MediaUpload classifyMedia(MoeraNode moeraNode, PrivateMediaFileInfo info) throws MoeraNodeException {
        UploadedMedia original = uploadedMedia(info);
        if (!Boolean.TRUE.equals(info.getUncompressed())) {
            return new MediaUpload.Ready(original);
        }
        if (info.getCompressedMediaId() == null || info.getCompressedMediaId().isBlank()) {
            return new MediaUpload.AwaitingCompression(original);
        }
        PrivateMediaFileInfo compressed = moeraNode.getPrivateMediaInfo(info.getCompressedMediaId(), null);
        return new MediaUpload.Ready(uploadedMedia(compressed));
    }

    private UploadedMedia uploadedMedia(PrivateMediaFileInfo info) {
        if (info == null || info.getId() == null || info.getHash() == null) {
            throw new MoeraPermanentException("Moera returned incomplete media information", null);
        }
        return new UploadedMedia(info.getId(), info.getHash());
    }

    private PostingText posting(PublicationPayload payload, boolean create) {
        PostingText posting = new PostingText();
        posting.setBodySrc(payload.bodySrc());
        posting.setBodySrcFormat(payload.bodySrcFormat());
        posting.setMedia(payload.media());
        if (create) {
            posting.setPublications(payload.publications());
        }
        return posting;
    }

    private RuntimeException externalFailure(String message, MoeraNodeException exception) {
        if (exception instanceof MoeraNodeApiException apiException) {
            if ("media.wrong-size".equals(apiException.getErrorCode())) {
                return new MoeraMediaTooLargeException(exception);
            }
            if (apiException.getStatus() == 429 || TEMPORARY_ERROR_CODES.contains(apiException.getErrorCode())) {
                return new MoeraTemporaryException(message, exception);
            }
            if (apiException.getStatus() < 500) {
                return new MoeraPermanentException(message, exception);
            }
        }
        return new MoeraTemporaryException(message, exception);
    }

    private RuntimeException mutatingFailure(String message, MoeraNodeException exception) {
        if (exception instanceof MoeraNodeApiException apiException) {
            if (apiException.getStatus() == 429) {
                return new MoeraTemporaryException(message, exception);
            }
            if (apiException.getStatus() < 500) {
                return new MoeraPermanentException(message, exception);
            }
        }
        return new MoeraOutcomeUnknownException(message, exception);
    }

    private record CachedCapabilities(DestinationCapabilities capabilities, Instant expiresAt) {
    }

}
