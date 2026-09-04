package org.moera.importer.moera;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
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
import org.moera.lib.node.types.AvatarAttributes;
import org.moera.lib.node.types.AvatarDescription;
import org.moera.lib.node.types.AvatarInfo;
import org.moera.lib.node.types.FeedReference;
import org.moera.lib.node.types.MediaUploadAttributes;
import org.moera.lib.node.types.MediaUploadInfo;
import org.moera.lib.node.types.PostingText;
import org.moera.lib.node.types.PostingOperations;
import org.moera.lib.node.types.PrivateMediaFileInfo;
import org.moera.lib.node.types.ProfileAttributes;
import org.moera.lib.node.types.PublicMediaFileInfo;
import org.moera.lib.node.types.RejectedReactions;
import org.moera.lib.node.types.SettingInfo;
import org.moera.lib.node.types.StoryAttributes;
import org.moera.lib.node.types.principal.Principal;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

@Service
public class MoeraService {

    private static final Duration CAPABILITIES_TTL = Duration.ofMinutes(5);
    private static final int MEDIA_UPLOAD_CHUNK_SIZE = 8 * 1024 * 1024;
    private static final int COPY_BUFFER_SIZE = 64 * 1024;
    private static final String EMPTY_BODY_CONTENT_TYPE = "application/octet-stream";
    private static final String MEDIA_MAX_SIZE = "media.max-size";
    private static final String CLIENT_POSTING_PREFIX = "client.mercy.posting.";
    private static final String VISIBILITY_DEFAULT = CLIENT_POSTING_PREFIX + "visibility.default";
    private static final String COMMENT_ADDITION_DEFAULT = CLIENT_POSTING_PREFIX + "comments.addition.default";
    private static final String REACTIONS_ENABLED_DEFAULT = CLIENT_POSTING_PREFIX + "reactions.enabled.default";
    private static final String NEGATIVE_REACTIONS_ENABLED_DEFAULT =
        CLIENT_POSTING_PREFIX + "reactions.negative.enabled.default";
    private static final String POSITIVE_REACTIONS_DISABLED_DEFAULT =
        CLIENT_POSTING_PREFIX + "reactions-disabled.positive.default";
    private static final String NEGATIVE_REACTIONS_DISABLED_DEFAULT =
        CLIENT_POSTING_PREFIX + "reactions-disabled.negative.default";
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
            PrivateMediaFileInfo info = Files.size(file) <= MEDIA_UPLOAD_CHUNK_SIZE
                ? moeraNode.uploadPrivateMedia(null, null, true, file, media.mimeType())
                : uploadMediaInChunks(moeraNode, file, media);
            return classifyMedia(moeraNode, info);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot prepare media chunks for Moera", e);
        } catch (MoeraNodeException e) {
            throw externalFailure("Cannot upload media to Moera", e);
        }
    }

    private PrivateMediaFileInfo uploadMediaInChunks(MoeraNode moeraNode, Path file, Media media)
        throws IOException, MoeraNodeException {
        long fileSize = Files.size(file);
        MediaUploadAttributes attributes = new MediaUploadAttributes();
        attributes.setMimeType(media.mimeType());
        if (!ObjectUtils.isEmpty(media.fileName()) && media.fileName().length() <= 255) {
            attributes.setTitle(media.fileName());
        }
        attributes.setFileSize(Math.toIntExact(fileSize));
        attributes.setChunkSize(MEDIA_UPLOAD_CHUNK_SIZE);
        MediaUploadInfo upload = moeraNode.createMediaUpload(attributes);
        String uploadId = upload != null ? upload.getId() : null;
        boolean finalized = false;
        try {
            if (ObjectUtils.isEmpty(uploadId) || upload.getChunkSize() <= 0) {
                throw new MoeraPermanentException("Moera returned incomplete media upload information", null);
            }
            uploadChunks(moeraNode, uploadId, upload.getChunkSize(), file, fileSize, media.mimeType());
            PrivateMediaFileInfo info = finalizeMediaUpload(moeraNode, uploadId);
            finalized = true;
            return info;
        } finally {
            if (!finalized && !ObjectUtils.isEmpty(uploadId)) {
                deleteMediaUploadQuietly(moeraNode, uploadId);
            }
        }
    }

    private void uploadChunks(
        MoeraNode moeraNode,
        String uploadId,
        int chunkSize,
        Path file,
        long fileSize,
        String mimeType
    ) throws IOException, MoeraNodeException {
        try (
            TemporaryChunkFile chunkFile = TemporaryChunkFile.create();
            InputStream input = Files.newInputStream(file)
        ) {
            byte[] buffer = new byte[Math.min(COPY_BUFFER_SIZE, chunkSize)];
            long remaining = fileSize;
            for (int chunk = 0; remaining > 0; chunk++) {
                int size = (int) Math.min(chunkSize, remaining);
                writeChunk(input, chunkFile.path(), buffer, size);
                moeraNode.uploadMediaChunk(uploadId, chunk, chunkFile.path(), mimeType);
                remaining -= size;
            }
        }
    }

    private PrivateMediaFileInfo finalizeMediaUpload(MoeraNode moeraNode, String uploadId)
        throws IOException, MoeraNodeException {
        TemporaryChunkFile requestBody = TemporaryChunkFile.create();
        PrivateMediaFileInfo info;
        try {
            info = moeraNode.uploadPrivateMedia(
                uploadId, null, true, requestBody.path(), EMPTY_BODY_CONTENT_TYPE
            );
        } catch (MoeraNodeException | RuntimeException | Error e) {
            requestBody.closeSuppressing(e);
            throw e;
        }
        requestBody.closeQuietly();
        return info;
    }

    private void writeChunk(InputStream input, Path chunkFile, byte[] buffer, int size) throws IOException {
        try (OutputStream output = Files.newOutputStream(
            chunkFile, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
        )) {
            int remaining = size;
            while (remaining > 0) {
                int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new EOFException("Media file ended before the declared size");
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private void deleteMediaUploadQuietly(MoeraNode moeraNode, String uploadId) {
        try {
            moeraNode.deleteMediaUpload(uploadId);
        } catch (MoeraNodeException | RuntimeException ignored) {
            // The upload expires automatically if cleanup cannot reach Moera.
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
        PostingText posting = posting(payload, true);
        applyProfileAvatar(posting, destination);
        applyClientPostingSettings(posting, loadClientPostingSettings(moeraNode));
        try {
            var created = moeraNode.createPosting(posting);
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

    public MoeraProfileAvatar updateProfile(Destination destination, MoeraProfileUpdate update) {
        MoeraNode moeraNode = moeraClientFactory.create(destination);
        try {
            MoeraProfileAvatar avatar = profileAvatar(moeraNode, update);
            ProfileAttributes profile = new ProfileAttributes();
            profile.setFullName(update.fullName());
            profile.setBioSrc(update.bioHtml());
            profile.setBioSrcFormat(org.moera.lib.node.types.SourceFormat.HTML);
            if (update.hasEmblem()) {
                profile.setAvatarId(avatar.avatarId());
            }
            moeraNode.updateProfile(profile);
            if (!update.hasEmblem() && !ObjectUtils.isEmpty(update.avatarId())) {
                deleteAvatar(moeraNode, update.avatarId());
            }
            return avatar;
        } catch (MoeraNodeException e) {
            throw mutatingFailure("Moera profile update outcome is unknown", e);
        }
    }

    private MoeraProfileAvatar profileAvatar(MoeraNode moeraNode, MoeraProfileUpdate update)
        throws MoeraNodeException {
        if (update.newEmblem() != null) {
            PublicMediaFileInfo media = moeraNode.uploadPublicMedia(update.newEmblem(), update.emblemMimeType());
            if (media == null || ObjectUtils.isEmpty(media.getId()) || media.getWidth() == null
                || media.getHeight() == null || media.getWidth() <= 0 || media.getHeight() <= 0) {
                throw new MoeraConfigurationException("Moera returned incomplete public emblem information");
            }
            int clipSize = Math.min(media.getWidth(), media.getHeight());
            AvatarAttributes attributes = new AvatarAttributes();
            attributes.setMediaId(media.getId());
            attributes.setClipX((media.getWidth() - clipSize) / 2);
            attributes.setClipY((media.getHeight() - clipSize) / 2);
            attributes.setClipSize(clipSize);
            attributes.setAvatarSize(200);
            attributes.setRotate(0);
            attributes.setShape("circle");
            AvatarInfo avatar = moeraNode.createAvatar(attributes);
            if (avatar == null || ObjectUtils.isEmpty(avatar.getId()) || ObjectUtils.isEmpty(avatar.getMediaId())) {
                throw new MoeraConfigurationException("Moera returned incomplete avatar information");
            }
            return new MoeraProfileAvatar(
                avatar.getId(),
                avatar.getMediaId(),
                ObjectUtils.isEmpty(avatar.getShape()) ? "circle" : avatar.getShape()
            );
        }
        if (update.hasEmblem()) {
            if (ObjectUtils.isEmpty(update.avatarId()) || ObjectUtils.isEmpty(update.avatarMediaId())) {
                throw new MoeraConfigurationException("Stored Moera avatar information is incomplete");
            }
            return new MoeraProfileAvatar(update.avatarId(), update.avatarMediaId(), "circle");
        }
        return new MoeraProfileAvatar("", null, null);
    }

    private void deleteAvatar(MoeraNode moeraNode, String avatarId) throws MoeraNodeException {
        try {
            moeraNode.deleteAvatar(avatarId);
        } catch (MoeraNodeApiNotFoundException ignored) {
            // A retry may observe an avatar deleted by the previous attempt.
        }
    }

    private void applyProfileAvatar(PostingText posting, Destination destination) {
        String mediaId = destination.avatarMediaId();
        if (ObjectUtils.isEmpty(mediaId)) {
            return;
        }
        AvatarDescription avatar = new AvatarDescription();
        avatar.setMediaId(mediaId);
        avatar.setShape(ObjectUtils.isEmpty(destination.avatarShape()) ? "circle" : destination.avatarShape());
        avatar.setOptional(true);
        posting.setOwnerAvatar(avatar);
    }

    private Map<String, String> loadClientPostingSettings(MoeraNode moeraNode) {
        Map<String, String> values = new HashMap<>();
        values.put(VISIBILITY_DEFAULT, "public");
        values.put(COMMENT_ADDITION_DEFAULT, "signed");
        values.put(REACTIONS_ENABLED_DEFAULT, "true");
        values.put(NEGATIVE_REACTIONS_ENABLED_DEFAULT, "true");
        values.put(POSITIVE_REACTIONS_DISABLED_DEFAULT, "");
        values.put(NEGATIVE_REACTIONS_DISABLED_DEFAULT, "*");
        try {
            SettingInfo[] settings = moeraNode.getClientSettings(CLIENT_POSTING_PREFIX);
            if (settings != null) {
                for (SettingInfo setting : settings) {
                    if (setting != null && values.containsKey(setting.getName()) && setting.getValue() != null) {
                        values.put(setting.getName(), setting.getValue());
                    }
                }
            }
            return values;
        } catch (MoeraNodeException e) {
            throw externalFailure("Cannot load Moera client posting settings", e);
        }
    }

    private void applyClientPostingSettings(PostingText posting, Map<String, String> settings) {
        boolean reactionsEnabled = booleanSetting(settings.get(REACTIONS_ENABLED_DEFAULT));
        boolean negativeReactionsEnabled = reactionsEnabled
            && booleanSetting(settings.get(NEGATIVE_REACTIONS_ENABLED_DEFAULT));
        PostingOperations operations = new PostingOperations();
        operations.setView(new Principal(settings.get(VISIBILITY_DEFAULT)));
        operations.setAddComment(new Principal(settings.get(COMMENT_ADDITION_DEFAULT)));
        operations.setAddReaction(reactionsEnabled ? Principal.SIGNED : Principal.NONE);
        operations.setAddNegativeReaction(negativeReactionsEnabled ? Principal.SIGNED : Principal.NONE);
        posting.setOperations(operations);
        RejectedReactions rejectedReactions = new RejectedReactions();
        rejectedReactions.setPositive(settings.get(POSITIVE_REACTIONS_DISABLED_DEFAULT));
        rejectedReactions.setNegative(settings.get(NEGATIVE_REACTIONS_DISABLED_DEFAULT));
        posting.setRejectedReactions(rejectedReactions);
    }

    private boolean booleanSetting(String value) {
        return "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "1".equals(value);
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
        posting.setExternalSourceUri(payload.externalSourceUri());
        if (create) {
            posting.setPublications(payload.publications());
        }
        return posting;
    }

    private RuntimeException externalFailure(String message, MoeraNodeException exception) {
        if (exception instanceof MoeraNodeApiException apiException) {
            String detailedMessage = apiFailureMessage(message, apiException);
            if ("media.wrong-size".equals(apiException.getErrorCode())) {
                return new MoeraMediaTooLargeException(exception);
            }
            if (
                apiException.getStatus() == 429
                || apiException.getErrorCode() != null && TEMPORARY_ERROR_CODES.contains(apiException.getErrorCode())
            ) {
                return new MoeraTemporaryException(detailedMessage, exception);
            }
            if (apiException.getStatus() < 500) {
                return new MoeraPermanentException(detailedMessage, exception);
            }
            return new MoeraTemporaryException(detailedMessage, exception);
        }
        return new MoeraTemporaryException(message, exception);
    }

    private String apiFailureMessage(String message, MoeraNodeApiException exception) {
        return "%s: status=%d, error_code=%s, response=%s".formatted(
            message, exception.getStatus(), exception.getErrorCode(), exception.getMessage()
        );
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

    private record TemporaryChunkFile(Path path) implements AutoCloseable {

        private static TemporaryChunkFile create() throws IOException {
            return new TemporaryChunkFile(Files.createTempFile("moera-media-", ".chunk"));
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
        }

        private void closeQuietly() {
            try {
                close();
            } catch (IOException ignored) {
                // A successful upload must not be retried because an empty temporary request body cannot be deleted.
            }
        }

        private void closeSuppressing(Throwable failure) {
            try {
                close();
            } catch (IOException e) {
                failure.addSuppressed(e);
            }
        }

    }

}
