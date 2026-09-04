package org.moera.importer.application;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.moera.importer.config.ProfileProperties;
import org.moera.importer.config.StorageProperties;
import org.moera.importer.model.AccessState;
import org.moera.importer.model.Destination;
import org.moera.importer.model.DestinationProfileState;
import org.moera.importer.model.SourceType;
import org.moera.importer.model.SubscriptionState;
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
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfileSynchronizerTest {

    @TempDir
    private Path temporaryDirectory;

    private ChannelRepository channelRepository;
    private DestinationRepository destinationRepository;
    private MirrorRepository mirrorRepository;
    private MoeraService moeraService;
    private SourceAdapter sourceAdapter;
    private ProfileSynchronizer profileSynchronizer;

    @BeforeEach
    void setUp() {
        channelRepository = mock(ChannelRepository.class);
        destinationRepository = mock(DestinationRepository.class);
        mirrorRepository = mock(MirrorRepository.class);
        moeraService = mock(MoeraService.class);
        sourceAdapter = mock(SourceAdapter.class);
        SourceAdapterRegistry sourceAdapterRegistry = mock(SourceAdapterRegistry.class);
        when(sourceAdapterRegistry.get(SourceType.TELEGRAM)).thenReturn(sourceAdapter);
        ProfileProperties profileProperties = new ProfileProperties();
        profileProperties.setContactEmail("import@moera.example");
        StorageProperties storageProperties = new StorageProperties();
        storageProperties.setStagingDirectory(temporaryDirectory);
        ChannelProfileRenderer channelProfileRenderer = new ChannelProfileRenderer();
        ReflectionTestUtils.setField(channelProfileRenderer, "profileProperties", profileProperties);
        profileSynchronizer = new ProfileSynchronizer();
        ReflectionTestUtils.setField(profileSynchronizer, "channelProfileRenderer", channelProfileRenderer);
        ReflectionTestUtils.setField(profileSynchronizer, "channelRepository", channelRepository);
        ReflectionTestUtils.setField(profileSynchronizer, "destinationRepository", destinationRepository);
        ReflectionTestUtils.setField(profileSynchronizer, "mirrorRepository", mirrorRepository);
        ReflectionTestUtils.setField(profileSynchronizer, "moeraService", moeraService);
        ReflectionTestUtils.setField(profileSynchronizer, "sourceAdapterRegistry", sourceAdapterRegistry);
        ReflectionTestUtils.setField(profileSynchronizer, "storageProperties", storageProperties);
    }

    @Test
    void uploadsChangedEmblemUpdatesProfileAndPersistsAvatarState() {
        ChannelSnapshot channel = channel();
        Destination destination = destination(null);
        when(channelRepository.find(1)).thenReturn(java.util.Optional.of(channel));
        when(mirrorRepository.findProfileDestinations(1)).thenReturn(List.of(destination));
        when(moeraService.updateProfile(any(), any())).thenReturn(
            new MoeraProfileAvatar("avatar-id", "avatar-media-id", "circle")
        );

        profileSynchronizer.synchronize(1);

        verify(sourceAdapter).downloadChannelEmblem(any(), any(), any());
        ArgumentCaptor<MoeraProfileUpdate> update = ArgumentCaptor.forClass(MoeraProfileUpdate.class);
        verify(moeraService).updateProfile(any(), update.capture());
        org.assertj.core.api.Assertions.assertThat(update.getValue().fullName()).isEqualTo("News");
        org.assertj.core.api.Assertions.assertThat(update.getValue().bioHtml())
            .contains("Description", "import@moera.example", "https://t.me/news");
        verify(destinationRepository).saveProfileState(
            org.mockito.ArgumentMatchers.eq(2L), any()
        );
    }

    @Test
    void skipsMoeraWhenRenderedFingerprintIsAlreadyStored() {
        ChannelSnapshot channel = channel();
        Destination first = destination(null);
        when(channelRepository.find(1)).thenReturn(java.util.Optional.of(channel));
        when(mirrorRepository.findProfileDestinations(1)).thenReturn(List.of(first));
        when(moeraService.updateProfile(any(), any())).thenReturn(
            new MoeraProfileAvatar("avatar-id", "avatar-media-id", "circle")
        );
        profileSynchronizer.synchronize(1);
        ArgumentCaptor<DestinationProfileState> state = ArgumentCaptor.forClass(DestinationProfileState.class);
        verify(destinationRepository).saveProfileState(org.mockito.ArgumentMatchers.eq(2L), state.capture());

        Destination synchronizedDestination = destination(state.getValue());
        when(mirrorRepository.findProfileDestinations(1)).thenReturn(List.of(synchronizedDestination));
        profileSynchronizer.synchronize(1);

        verify(moeraService, org.mockito.Mockito.times(1)).updateProfile(any(), any());
        verify(sourceAdapter, org.mockito.Mockito.times(1)).downloadChannelEmblem(any(), any(), any());
    }

    private ChannelSnapshot channel() {
        ChannelMetadata metadata = new ChannelMetadata(
            "News", "<p>Description</p>", "news",
            new ChannelMetadata.Emblem("remote-photo", "unique-photo", "image/jpeg")
        );
        return new ChannelSnapshot(
            1, SourceType.TELEGRAM, "-1001", URI.create("https://t.me/news"),
            JsonNodeFactory.instance.objectNode(), metadata,
            AccessState.GRANTED, JsonNodeFactory.instance.objectNode(), SubscriptionState.ACTIVE,
            JsonNodeFactory.instance.objectNode(), null
        );
    }

    private Destination destination(DestinationProfileState state) {
        return new Destination(
            2,
            "news",
            "env:TEST",
            true,
            state != null ? state.fingerprint() : null,
            state != null ? state.sourceEmblemId() : null,
            state != null ? state.avatarId() : null,
            state != null ? state.avatarMediaId() : null,
            state != null ? state.avatarShape() : null,
            Instant.EPOCH, Instant.EPOCH
        );
    }

}
