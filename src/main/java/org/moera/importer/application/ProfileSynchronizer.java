package org.moera.importer.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.moera.importer.config.StorageProperties;
import org.moera.importer.model.Destination;
import org.moera.importer.moera.MoeraProfileAvatar;
import org.moera.importer.moera.MoeraProfileUpdate;
import org.moera.importer.moera.MoeraService;
import org.moera.importer.persistence.ChannelRepository;
import org.moera.importer.persistence.DestinationRepository;
import org.moera.importer.persistence.MirrorRepository;
import org.moera.importer.source.ChannelMetadata;
import org.moera.importer.source.ChannelSnapshot;
import org.moera.importer.source.SourceAdapter;
import org.moera.importer.source.SourceAdapterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

@Service
public class ProfileSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(ProfileSynchronizer.class);

    @Inject
    private ChannelProfileRenderer channelProfileRenderer;

    @Inject
    private ChannelRepository channelRepository;

    @Inject
    private DestinationRepository destinationRepository;

    @Inject
    private MirrorRepository mirrorRepository;

    @Inject
    private MoeraService moeraService;

    @Inject
    private SourceAdapterRegistry sourceAdapterRegistry;

    @Inject
    private StorageProperties storageProperties;

    public void synchronize(long channelId) {
        ChannelSnapshot channel = channelRepository.find(channelId).orElse(null);
        if (channel == null) {
            return;
        }
        ChannelMetadata metadata = channel.metadata();
        if (metadata == null) {
            return;
        }
        for (Destination destination : mirrorRepository.findProfileDestinations(channelId)) {
            try {
                synchronize(channel, metadata, destination);
            } catch (RuntimeException | IOException e) {
                log.warn(
                    "Profile synchronization failed: channel_id={}, destination_id={}, error={}",
                    channelId,
                    destination.id(),
                    diagnostic(e)
                );
            }
        }
    }

    private void synchronize(ChannelSnapshot channel, ChannelMetadata metadata, Destination destination)
        throws IOException {
        RenderedChannelProfile rendered = channelProfileRenderer.render(metadata);
        String fingerprint = fingerprint(rendered, metadata);
        JsonNode stored = destination.data().path("profile");
        if (fingerprint.equals(stored.path("fingerprint").textValue())) {
            return;
        }

        boolean emblemChanged = emblemChanged(metadata.emblem(), stored);
        Path emblemFile = null;
        try {
            if (metadata.emblem() != null && emblemChanged) {
                Files.createDirectories(storageProperties.getStagingDirectory());
                emblemFile = Files.createTempFile(storageProperties.getStagingDirectory(), "channel-emblem-", ".jpg");
                SourceAdapter sourceAdapter = sourceAdapterRegistry.get(channel.sourceType());
                sourceAdapter.downloadChannelEmblem(channel, metadata.emblem(), emblemFile);
            }
            MoeraProfileAvatar avatar = moeraService.updateProfile(destination, new MoeraProfileUpdate(
                rendered.fullName(),
                rendered.bioHtml(),
                emblemFile,
                metadata.emblem() != null ? metadata.emblem().mimeType() : null,
                stored.path("avatarId").textValue(),
                stored.path("avatarMediaId").textValue(),
                metadata.emblem() != null
            ));
            destinationRepository.saveProfileState(destination.id(), profileState(fingerprint, metadata, avatar));
        } finally {
            if (emblemFile != null) {
                Files.deleteIfExists(emblemFile);
            }
        }
    }

    private boolean emblemChanged(ChannelMetadata.Emblem emblem, JsonNode stored) {
        if (emblem == null) {
            return !ObjectUtils.isEmpty(stored.path("sourceEmblemUniqueId").textValue());
        }
        return !emblem.uniqueId().equals(stored.path("sourceEmblemUniqueId").textValue())
            || ObjectUtils.isEmpty(stored.path("avatarId").textValue())
            || ObjectUtils.isEmpty(stored.path("avatarMediaId").textValue());
    }

    private ObjectNode profileState(
        String fingerprint,
        ChannelMetadata metadata,
        MoeraProfileAvatar avatar
    ) {
        ObjectNode state = JsonNodeFactory.instance.objectNode().put("fingerprint", fingerprint);
        if (metadata.emblem() != null) {
            state.put("sourceEmblemUniqueId", metadata.emblem().uniqueId());
        }
        if (!ObjectUtils.isEmpty(avatar.avatarId())) {
            state.put("avatarId", avatar.avatarId());
        }
        if (!ObjectUtils.isEmpty(avatar.mediaId())) {
            state.put("avatarMediaId", avatar.mediaId());
        }
        if (!ObjectUtils.isEmpty(avatar.shape())) {
            state.put("avatarShape", avatar.shape());
        }
        return state;
    }

    private String fingerprint(RenderedChannelProfile rendered, ChannelMetadata metadata) {
        String emblemId = metadata.emblem() != null ? metadata.emblem().uniqueId() : "";
        String value = rendered.fullName() + '\u0000' + rendered.bioHtml() + '\u0000' + emblemId;
        return Sha256.hex(value.getBytes(StandardCharsets.UTF_8));
    }

    private String diagnostic(Exception exception) {
        return exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
    }

}
