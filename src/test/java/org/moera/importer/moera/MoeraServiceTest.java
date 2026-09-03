package org.moera.importer.moera;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.moera.importer.application.PublicationPayload;
import org.moera.importer.model.Destination;
import org.moera.importer.model.Media;
import org.moera.lib.node.MoeraNode;
import org.moera.lib.node.exception.MoeraNodeApiNotFoundException;
import org.moera.lib.node.exception.MoeraNodeApiOperationException;
import org.moera.lib.node.exception.MoeraNodeApiValidationException;
import org.moera.lib.node.exception.MoeraNodeConnectionException;
import org.moera.lib.node.types.Features;
import org.moera.lib.node.types.FeedReference;
import org.moera.lib.node.types.MediaToAttach;
import org.moera.lib.node.types.PostingFeatures;
import org.moera.lib.node.types.PostingInfo;
import org.moera.lib.node.types.PostingText;
import org.moera.lib.node.types.PrivateMediaFileInfo;
import org.moera.lib.node.types.SettingInfo;
import org.moera.lib.node.types.SourceFormat;
import org.moera.lib.node.types.StoryAttributes;
import org.moera.lib.node.types.body.Body;
import org.moera.lib.node.types.principal.Principal;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MoeraServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    @TempDir
    private Path temporaryDirectory;

    private MoeraClientFactory moeraClientFactory;
    private MoeraNode moeraNode;
    private MoeraService moeraService;
    private Destination destination;

    @BeforeEach
    void setUp() {
        moeraClientFactory = mock(MoeraClientFactory.class);
        moeraNode = mock(MoeraNode.class);
        moeraService = new MoeraService();
        ReflectionTestUtils.setField(moeraService, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(moeraService, "moeraClientFactory", moeraClientFactory);
        destination = new Destination(
            1, "alice", JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode(), NOW, NOW
        );
        when(moeraClientFactory.create(destination)).thenReturn(moeraNode);
    }

    @Test
    void loadsAndCachesMediaLimitFromSettingsAndFormatsFromFeatures() throws Exception {
        Features features = new Features();
        PostingFeatures posting = new PostingFeatures();
        posting.setSourceFormats(List.of(SourceFormat.HTML, SourceFormat.PLAIN_TEXT));
        features.setPosting(posting);
        SettingInfo setting = new SettingInfo();
        setting.setName("media.max-size");
        setting.setValue("157286400");
        when(moeraNode.getFeatures()).thenReturn(features);
        when(moeraNode.getNodeSettings("media.max-size")).thenReturn(new SettingInfo[] { setting });

        DestinationCapabilities first = moeraService.capabilities(destination);
        DestinationCapabilities second = moeraService.capabilities(destination);

        assertThat(first.mediaMaxSize()).isEqualTo(157286400);
        assertThat(first.sourceFormats()).containsExactly(SourceFormat.HTML, SourceFormat.PLAIN_TEXT);
        assertThat(second).isSameAs(first);
        verify(moeraNode, times(1)).getFeatures();
        verify(moeraNode, times(1)).getNodeSettings("media.max-size");
    }

    @Test
    void reloadsCapabilitiesAfterBoundedTtl() throws Exception {
        Features features = new Features();
        PostingFeatures posting = new PostingFeatures();
        posting.setSourceFormats(List.of(SourceFormat.HTML));
        features.setPosting(posting);
        SettingInfo setting = new SettingInfo();
        setting.setName("media.max-size");
        setting.setValue("1000");
        when(moeraNode.getFeatures()).thenReturn(features);
        when(moeraNode.getNodeSettings("media.max-size")).thenReturn(new SettingInfo[] { setting });

        moeraService.capabilities(destination);
        ReflectionTestUtils.setField(
            moeraService, "clock", Clock.fixed(NOW.plus(Duration.ofMinutes(6)), ZoneOffset.UTC)
        );
        moeraService.capabilities(destination);

        verify(moeraClientFactory, times(2)).create(destination);
        verify(moeraNode, times(2)).getFeatures();
    }

    @Test
    void alwaysRequestsDownsizeAndPreservesMediaIdAndHash() throws Exception {
        Path file = temporaryDirectory.resolve("image.jpg");
        PrivateMediaFileInfo info = mediaInfo("media-id", "media-hash", false, null);
        when(moeraNode.uploadPrivateMedia(null, null, true, file, "image/jpeg")).thenReturn(info);

        MediaUpload upload = moeraService.uploadMedia(destination, file, media());

        assertThat(upload).isEqualTo(new MediaUpload.Ready(new UploadedMedia("media-id", "media-hash")));
        verify(moeraNode).uploadPrivateMedia(null, null, true, file, "image/jpeg");
    }

    @Test
    void reportsUncompressedUploadAsAwaitingCompression() throws Exception {
        Path file = temporaryDirectory.resolve("video.mp4");
        when(moeraNode.uploadPrivateMedia(null, null, true, file, "image/jpeg"))
            .thenReturn(mediaInfo("original-id", "original-hash", true, null));

        MediaUpload upload = moeraService.uploadMedia(destination, file, media());

        assertThat(upload)
            .isEqualTo(new MediaUpload.AwaitingCompression(new UploadedMedia("original-id", "original-hash")));
    }

    @Test
    void keepsWaitingUntilCompressedMediaAppears() throws Exception {
        when(moeraNode.getPrivateMediaInfo("original-id", null))
            .thenReturn(mediaInfo("original-id", "original-hash", true, null));

        MediaUpload upload = moeraService.checkMediaCompression(destination, "original-id");

        assertThat(upload)
            .isEqualTo(new MediaUpload.AwaitingCompression(new UploadedMedia("original-id", "original-hash")));
    }

    @Test
    void returnsCompressedMediaIdAndHashWhenCompressionFinishes() throws Exception {
        when(moeraNode.getPrivateMediaInfo("original-id", null))
            .thenReturn(mediaInfo("original-id", "original-hash", true, "compressed-id"));
        when(moeraNode.getPrivateMediaInfo("compressed-id", null))
            .thenReturn(mediaInfo("compressed-id", "compressed-hash", false, null));

        MediaUpload upload = moeraService.checkMediaCompression(destination, "original-id");

        assertThat(upload)
            .isEqualTo(new MediaUpload.Ready(new UploadedMedia("compressed-id", "compressed-hash")));
    }

    @Test
    void returnsAlreadyCompressedMediaImmediatelyAfterUpload() throws Exception {
        Path file = temporaryDirectory.resolve("video.mp4");
        when(moeraNode.uploadPrivateMedia(null, null, true, file, "image/jpeg"))
            .thenReturn(mediaInfo("original-id", "original-hash", true, "compressed-id"));
        when(moeraNode.getPrivateMediaInfo("compressed-id", null))
            .thenReturn(mediaInfo("compressed-id", "compressed-hash", false, null));

        MediaUpload upload = moeraService.uploadMedia(destination, file, media());

        assertThat(upload)
            .isEqualTo(new MediaUpload.Ready(new UploadedMedia("compressed-id", "compressed-hash")));
    }

    @Test
    void createsPostingSynchronouslyAndReturnsStoryIds() throws Exception {
        PostingInfo created = new PostingInfo();
        created.setId("posting-id");
        created.setFeedReferences(List.of(feedReference("timeline", "story-id")));
        when(moeraNode.createPosting(any())).thenReturn(created);
        PublicationPayload payload = payload();

        CreatedPosting result = moeraService.createPost(destination, payload);

        assertThat(result).isEqualTo(new CreatedPosting("posting-id", java.util.Map.of("timeline", "story-id")));
        ArgumentCaptor<PostingText> posting = ArgumentCaptor.forClass(PostingText.class);
        verify(moeraNode).createPosting(posting.capture());
        assertThat(posting.getValue().getBodySrc()).isSameAs(payload.bodySrc());
        assertThat(posting.getValue().getPublications()).isEqualTo(payload.publications());
        assertThat(posting.getValue().getOperations().getView()).isEqualTo(Principal.PUBLIC);
        assertThat(posting.getValue().getOperations().getAddComment()).isEqualTo(Principal.SIGNED);
        assertThat(posting.getValue().getOperations().getAddReaction()).isEqualTo(Principal.SIGNED);
        assertThat(posting.getValue().getOperations().getAddNegativeReaction()).isEqualTo(Principal.SIGNED);
        assertThat(posting.getValue().getRejectedReactions().getPositive()).isEmpty();
        assertThat(posting.getValue().getRejectedReactions().getNegative()).isEqualTo("*");
        verify(moeraNode).getClientSettings("client.mercy.posting.");
        verify(moeraNode, never()).createRemotePosting(anyString(), any());
    }

    @Test
    void reloadsClientPostingSettingsBeforeEveryCreate() throws Exception {
        PostingInfo created = new PostingInfo();
        created.setId("posting-id");
        when(moeraNode.getClientSettings("client.mercy.posting.")).thenReturn(
            settings(
                "client.mercy.posting.visibility.default", "friends",
                "client.mercy.posting.comments.addition.default", "subscribed",
                "client.mercy.posting.reactions.enabled.default", "false",
                "client.mercy.posting.reactions.negative.enabled.default", "true",
                "client.mercy.posting.reactions-disabled.positive.default", "2764",
                "client.mercy.posting.reactions-disabled.negative.default", "1f44e"
            ),
            settings(
                "client.mercy.posting.visibility.default", "private",
                "client.mercy.posting.comments.addition.default", "none",
                "client.mercy.posting.reactions.enabled.default", "true",
                "client.mercy.posting.reactions.negative.enabled.default", "false",
                "client.mercy.posting.reactions-disabled.positive.default", "",
                "client.mercy.posting.reactions-disabled.negative.default", "*"
            )
        );
        when(moeraNode.createPosting(any())).thenReturn(created);

        moeraService.createPost(destination, payload());
        moeraService.createPost(destination, payload());

        ArgumentCaptor<PostingText> posting = ArgumentCaptor.forClass(PostingText.class);
        verify(moeraNode, times(2)).createPosting(posting.capture());
        PostingText first = posting.getAllValues().get(0);
        PostingText second = posting.getAllValues().get(1);
        assertThat(first.getOperations().getView()).isEqualTo(new Principal("friends"));
        assertThat(first.getOperations().getAddComment()).isEqualTo(Principal.SUBSCRIBED);
        assertThat(first.getOperations().getAddReaction()).isEqualTo(Principal.NONE);
        assertThat(first.getOperations().getAddNegativeReaction()).isEqualTo(Principal.NONE);
        assertThat(first.getRejectedReactions().getPositive()).isEqualTo("2764");
        assertThat(first.getRejectedReactions().getNegative()).isEqualTo("1f44e");
        assertThat(second.getOperations().getView()).isEqualTo(Principal.PRIVATE);
        assertThat(second.getOperations().getAddComment()).isEqualTo(Principal.NONE);
        assertThat(second.getOperations().getAddReaction()).isEqualTo(Principal.SIGNED);
        assertThat(second.getOperations().getAddNegativeReaction()).isEqualTo(Principal.NONE);
        assertThat(second.getRejectedReactions().getPositive()).isEmpty();
        assertThat(second.getRejectedReactions().getNegative()).isEqualTo("*");
        var order = inOrder(moeraNode);
        order.verify(moeraNode).getClientSettings("client.mercy.posting.");
        order.verify(moeraNode).createPosting(any());
        order.verify(moeraNode).getClientSettings("client.mercy.posting.");
        order.verify(moeraNode).createPosting(any());
    }

    @Test
    void updatesPostingSynchronouslyWithoutPublications() throws Exception {
        PublicationPayload payload = payload();

        moeraService.updatePost(destination, "posting-id", payload);

        ArgumentCaptor<PostingText> posting = ArgumentCaptor.forClass(PostingText.class);
        verify(moeraNode).updatePosting(anyString(), posting.capture());
        assertThat(posting.getValue().getBodySrc()).isSameAs(payload.bodySrc());
        assertThat(posting.getValue().getMedia()).isEqualTo(payload.media());
        assertThat(posting.getValue().getPublications()).isNull();
        verify(moeraNode, never()).updateRemotePosting(anyString(), anyString(), any());
    }

    @Test
    void updatesOnlyPinStateOnExistingStory() throws Exception {
        moeraService.updateStory(destination, "story-id", true);

        ArgumentCaptor<StoryAttributes> story = ArgumentCaptor.forClass(StoryAttributes.class);
        verify(moeraNode).updateStory(anyString(), story.capture());
        assertThat(story.getValue().getPinned()).isTrue();
        assertThat(story.getValue().getFeedName()).isNull();
        assertThat(story.getValue().getPublishAt()).isNull();
    }

    @Test
    void treatsDeleteNotFoundAsSuccess() throws Exception {
        when(moeraNode.deletePosting("missing")).thenThrow(new MoeraNodeApiNotFoundException("posting.not-found", ""));

        moeraService.deletePost(destination, "missing");

        verify(moeraNode).deletePosting("missing");
    }

    @Test
    void mapsWrongSizeToTerminalMediaOutcome() throws Exception {
        Path file = temporaryDirectory.resolve("large.jpg");
        when(moeraNode.uploadPrivateMedia(isNull(), isNull(), any(), any(), any()))
            .thenThrow(new MoeraNodeApiValidationException("media.wrong-size", "too large"));

        assertThatThrownBy(() -> moeraService.uploadMedia(destination, file, media()))
            .isInstanceOf(MoeraMediaTooLargeException.class);
    }

    @Test
    void mapsMediaStorageErrorToRetryableOutcome() throws Exception {
        Path file = temporaryDirectory.resolve("image.jpg");
        when(moeraNode.uploadPrivateMedia(isNull(), isNull(), any(), any(), any()))
            .thenThrow(new MoeraNodeApiOperationException("media.storage-error", "storage unavailable"));

        assertThatThrownBy(() -> moeraService.uploadMedia(destination, file, media()))
            .isInstanceOf(MoeraTemporaryException.class);
    }

    @Test
    void mapsUnknownCreateOutcomeSeparately() throws Exception {
        when(moeraNode.createPosting(any()))
            .thenThrow(new MoeraNodeConnectionException(new IOException("connection reset")));

        assertThatThrownBy(() -> moeraService.createPost(destination, payload()))
            .isInstanceOf(MoeraOutcomeUnknownException.class);
    }

    @Test
    void doesNotAttemptCreateWhenClientSettingsCannotBeLoaded() throws Exception {
        when(moeraNode.getClientSettings("client.mercy.posting."))
            .thenThrow(new MoeraNodeConnectionException(new IOException("connection reset")));

        assertThatThrownBy(() -> moeraService.createPost(destination, payload()))
            .isInstanceOf(MoeraTemporaryException.class);

        verify(moeraNode, never()).createPosting(any());
    }

    private PublicationPayload payload() {
        Body body = new Body();
        body.setText("text");
        MediaToAttach media = new MediaToAttach();
        media.setLocalMediaId("media-id");
        StoryAttributes publication = new StoryAttributes();
        publication.setFeedName("timeline");
        publication.setPublishAt(NOW.getEpochSecond());
        publication.setPinned(true);
        return new PublicationPayload(
            body, SourceFormat.HTML, List.of(media), List.of(publication), true
        );
    }

    private Media media() {
        return new Media(
            1, 1, "source-media", 0, "image/jpeg", "image.jpg", 12L, new byte[] { 1 },
            JsonNodeFactory.instance.objectNode(), NOW, NOW
        );
    }

    private PrivateMediaFileInfo mediaInfo(String id, String hash, boolean uncompressed, String compressedMediaId) {
        PrivateMediaFileInfo info = new PrivateMediaFileInfo();
        info.setId(id);
        info.setHash(hash);
        info.setUncompressed(uncompressed);
        info.setCompressedMediaId(compressedMediaId);
        return info;
    }

    private FeedReference feedReference(String feedName, String storyId) {
        FeedReference reference = new FeedReference();
        reference.setFeedName(feedName);
        reference.setStoryId(storyId);
        return reference;
    }

    private SettingInfo[] settings(String... values) {
        SettingInfo[] settings = new SettingInfo[values.length / 2];
        for (int i = 0; i < settings.length; i++) {
            SettingInfo setting = new SettingInfo();
            setting.setName(values[i * 2]);
            setting.setValue(values[i * 2 + 1]);
            settings[i] = setting;
        }
        return settings;
    }

}
