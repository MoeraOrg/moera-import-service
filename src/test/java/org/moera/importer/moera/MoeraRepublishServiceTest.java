package org.moera.importer.moera;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.moera.importer.model.Destination;
import org.moera.lib.node.MoeraNode;
import org.moera.lib.node.exception.MoeraNodeApiException;
import org.moera.lib.node.exception.MoeraNodeApiNotFoundException;
import org.moera.lib.node.exception.MoeraNodeConnectionException;
import org.moera.lib.node.types.PostingInfo;
import org.moera.lib.node.types.PostingRepublishAttributes;
import org.moera.lib.node.types.RemotePostingRepublishAttributes;
import org.moera.lib.node.types.StoryAttributes;
import org.moera.lib.node.types.StoryInfo;
import org.moera.lib.node.types.StoryType;
import org.moera.lib.node.types.principal.Principal;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MoeraRepublishServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final String URI = "https://t.me/forwarding/10";

    private final Destination destination = new Destination(1, "forwarding_0", NOW, NOW);
    private MoeraNode moeraNode;
    private MoeraService moeraService;

    @BeforeEach
    void setUp() {
        MoeraClientFactory factory = mock(MoeraClientFactory.class);
        moeraNode = mock(MoeraNode.class);
        when(factory.create(destination)).thenReturn(moeraNode);
        moeraService = new MoeraService();
        ReflectionTestUtils.setField(moeraService, "moeraClientFactory", factory);
    }

    @Test
    void republishesRemoteOriginalWithForwardingUrlsOnStories() throws Exception {
        StoryAttributes publication = publication();

        moeraService.republish(destination, "original_0", "original-post", List.of(publication));

        var attributes = ArgumentCaptor.forClass(RemotePostingRepublishAttributes.class);
        verify(moeraNode).republishRemotePosting(eq("original_0"), eq("original-post"), attributes.capture());
        assertThatCode(attributes.getValue()::validate).doesNotThrowAnyException();
        assertThat(attributes.getValue().getRemoteFeedName()).isEqualTo("timeline");
        assertThat(attributes.getValue().getPostingView()).isEqualTo(Principal.PUBLIC);
        assertThat(attributes.getValue().getPublications()).containsExactly(publication);
        assertThat(attributes.getValue().getPublications().getFirst().getExternalSourceUri()).containsExactly(URI);
        verify(moeraNode, never()).createPosting(any());
    }

    @Test
    void republishesLocallyWhenOriginalIsOnTheSameNode() throws Exception {
        moeraService.republish(destination, "forwarding_0", "local-post", List.of(publication()));

        var attributes = ArgumentCaptor.forClass(PostingRepublishAttributes.class);
        verify(moeraNode).republishPosting(eq("local-post"), attributes.capture());
        assertThat(attributes.getValue().getPublications().getFirst().getExternalSourceUri()).containsExactly(URI);
        verify(moeraNode, never()).republishRemotePosting(any(), any(), any());
    }

    @Test
    void discoversOnlyRequestedFeedsAndOriginalPostingAcrossAllLookupUris() throws Exception {
        StoryInfo unrelated = story("wrong", "timeline", "other_0", "original-post");
        StoryInfo wrongPost = story("wrong-post", "timeline", "original_0", "other-post");
        StoryInfo wrongFeed = story("wrong-feed", "news", "original_0", "original-post");
        StoryInfo correct = story("story-1", "timeline", "original_0", "original-post");
        when(moeraNode.getStoriesByExternalSource(URI))
            .thenReturn(new StoryInfo[] { unrelated, wrongPost, wrongFeed });
        when(moeraNode.getStoriesByExternalSource("https://t.me/forwarding/11"))
            .thenReturn(new StoryInfo[] { correct });

        var found = moeraService.findRepublishedStories(destination, "original_0", "original-post",
            List.of(URI, "https://t.me/forwarding/11"), List.of("timeline"));

        assertThat(found).containsOnlyKeys("timeline").containsEntry("timeline", "story-1");
    }

    @Test
    void discoversLocalPostingWithoutRemoteReceiverIdentity() throws Exception {
        StoryInfo story = story("local-story", "timeline", null, null);
        story.getPosting().setId("local-post");
        when(moeraNode.getStoriesByExternalSource(URI)).thenReturn(new StoryInfo[] { story });

        assertThat(moeraService.findRepublishedStories(destination, "forwarding_0", "local-post",
            List.of(URI), List.of("timeline"))).containsEntry("timeline", "local-story");
    }

    @Test
    void doesNotChooseArbitrarilyBetweenDuplicateStoriesInTheSameFeed() throws Exception {
        when(moeraNode.getStoriesByExternalSource(URI)).thenReturn(new StoryInfo[] {
            story("first", "timeline", "original_0", "original-post"),
            story("second", "timeline", "original_0", "original-post")
        });

        assertThatThrownBy(() -> moeraService.findRepublishedStories(destination, "original_0", "original-post",
            List.of(URI), List.of("timeline"))).isInstanceOf(MoeraPermanentException.class);
    }

    @Test
    void replacesOnlyForwardingUrlsWhenUpdatingStory() throws Exception {
        moeraService.updateStorySources(destination, "story-1", List.of(URI));

        var attributes = ArgumentCaptor.forClass(StoryAttributes.class);
        verify(moeraNode).updateStory(eq("story-1"), attributes.capture());
        assertThat(attributes.getValue().getExternalSourceUri()).containsExactly(URI);
        assertThat(attributes.getValue().getPublishAt()).isNull();
        assertThat(attributes.getValue().getPinned()).isNull();
        verify(moeraNode, never()).updatePosting(any(), any());
    }

    @Test
    void treatsAnAlreadyDeletedStoryAsSuccess() throws Exception {
        when(moeraNode.deleteStory("missing"))
            .thenThrow(new MoeraNodeApiNotFoundException("story.not-found", ""));

        moeraService.deleteStory(destination, "missing");

        verify(moeraNode).deleteStory("missing");
        verify(moeraNode, never()).deletePosting(any());
    }

    @Test
    void diagnosesUnknownRepublishAndRetryableDiscoveryFailures() throws Exception {
        when(moeraNode.republishRemotePosting(any(), any(), any()))
            .thenThrow(new MoeraNodeConnectionException(new java.io.IOException("Connection lost")));
        when(moeraNode.getStoriesByExternalSource(URI))
            .thenThrow(new MoeraNodeConnectionException(new java.io.IOException("Connection lost")));

        assertThatThrownBy(() -> moeraService.republish(destination, "original_0", "original-post",
            List.of(publication()))).isInstanceOf(MoeraOutcomeUnknownException.class);
        assertThatThrownBy(() -> moeraService.findRepublishedStories(destination, "original_0", "original-post",
            List.of(URI), List.of("timeline"))).isInstanceOf(MoeraTemporaryException.class);
    }

    @Test
    void preservesServerErrorCodeWhenRepublishIsRateLimited() throws Exception {
        var failure = new MoeraNodeApiException(429, "too-many-requests", "slow down");
        when(moeraNode.republishRemotePosting(any(), any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> moeraService.republish(destination, "original_0", "original-post",
            List.of(publication())))
            .isInstanceOf(MoeraTemporaryException.class)
            .hasMessageContainingAll("status=429", "error_code=too-many-requests")
            .hasCause(failure);
    }

    @Test
    void preservesServerErrorCodeWhenRepublishOutcomeIsUnknown() throws Exception {
        var failure = new MoeraNodeApiException(503, "node.unavailable", "maintenance");
        when(moeraNode.republishRemotePosting(any(), any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> moeraService.republish(destination, "original_0", "original-post",
            List.of(publication())))
            .isInstanceOf(MoeraOutcomeUnknownException.class)
            .hasMessageContainingAll("status=503", "error_code=node.unavailable")
            .hasCause(failure);
    }

    private StoryAttributes publication() {
        StoryAttributes publication = new StoryAttributes();
        publication.setFeedName("timeline");
        publication.setPublishAt(NOW.getEpochSecond());
        publication.setPinned(true);
        publication.setExternalSourceUri(List.of(URI));
        return publication;
    }

    private StoryInfo story(String id, String feed, String nodeName, String postingId) {
        StoryInfo story = new StoryInfo();
        story.setId(id);
        story.setFeedName(feed);
        story.setStoryType(StoryType.POSTING_ADDED);
        PostingInfo posting = new PostingInfo();
        posting.setId("local-copy");
        posting.setReceiverName(nodeName);
        posting.setReceiverPostingId(postingId);
        story.setPosting(posting);
        return story;
    }

}
