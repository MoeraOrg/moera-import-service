package org.moera.importer.application;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moera.importer.model.AccessState;
import org.moera.importer.model.ChannelOperation;
import org.moera.importer.model.SourceType;
import org.moera.importer.model.SubscriptionState;
import org.moera.importer.persistence.ChannelClaim;
import org.moera.importer.persistence.ChannelRepository;
import org.moera.importer.persistence.MessageRepository;
import org.moera.importer.source.AccessResult;
import org.moera.importer.source.ChannelResolution;
import org.moera.importer.source.ChannelSnapshot;
import org.moera.importer.source.GroupingRelation;
import org.moera.importer.source.MediaSnapshot;
import org.moera.importer.source.PollResult;
import org.moera.importer.source.SourceAdapter;
import org.moera.importer.source.SourceAdapterRegistry;
import org.moera.importer.source.SourceEventSink;
import org.moera.importer.source.SourceMessage;
import org.moera.importer.source.SourcePermanentException;
import org.moera.importer.source.SourceTemporaryException;
import org.moera.importer.source.SubscriptionResult;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelProcessorTest {

    private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");

    private ChannelProcessor channelProcessor;
    private RecordingChannelRepository channelRepository;
    private RecordingMessageRepository messageRepository;
    private FakeSourceAdapter sourceAdapter;

    @BeforeEach
    void configureProcessor() {
        channelProcessor = new ChannelProcessor();
        channelRepository = new RecordingChannelRepository();
        messageRepository = new RecordingMessageRepository();
        sourceAdapter = new FakeSourceAdapter();
        RetryPolicy retryPolicy = new RetryPolicy(
            Duration.ofMinutes(1),
            Duration.ofHours(1),
            0,
            () -> 0.5
        );
        ReflectionTestUtils.setField(channelProcessor, "channelRepository", channelRepository);
        ReflectionTestUtils.setField(channelProcessor, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(channelProcessor, "messageRepository", messageRepository);
        ReflectionTestUtils.setField(channelProcessor, "retryPolicy", retryPolicy);
        ReflectionTestUtils.setField(
            channelProcessor,
            "sourceAdapterRegistry",
            new FakeSourceAdapterRegistry(sourceAdapter)
        );
    }

    @Test
    void completesSuccessfulAccess() {
        ChannelClaim claim = claim(ChannelOperation.ACCESS);
        AccessResult result = new AccessResult(
            AccessState.GRANTED,
            "channel-1",
            JsonNodeFactory.instance.objectNode().put("member", true)
        );
        sourceAdapter.accessResult = result;

        channelProcessor.process(claim);

        assertThat(channelRepository.completedAccess).isEqualTo(result);
        assertThat(channelRepository.completedToken).isEqualTo(claim.processingToken());
        assertThat(messageRepository.pollResult).isNull();
    }

    @Test
    void completesSuccessfulSubscription() {
        ChannelClaim claim = claim(ChannelOperation.SUBSCRIPTION);
        SubscriptionResult result = new SubscriptionResult(
            SubscriptionState.ACTIVE,
            JsonNodeFactory.instance.objectNode().put("active", true)
        );
        sourceAdapter.subscriptionResult = result;

        channelProcessor.process(claim);

        assertThat(channelRepository.completedSubscription).isEqualTo(result);
        assertThat(channelRepository.completedToken).isEqualTo(claim.processingToken());
        assertThat(messageRepository.pollResult).isNull();
    }

    @Test
    void persistsSuccessfulPollAndWaterlineTogether() {
        ChannelClaim claim = claim(ChannelOperation.POLL);
        PollResult result = new PollResult(
            List.of(),
            JsonNodeFactory.instance.objectNode().put("messageId", 42)
        );
        sourceAdapter.pollResult = result;
        sourceAdapter.pollingInterval = Duration.ofMinutes(30);

        channelProcessor.process(claim);

        assertThat(messageRepository.channelId).isEqualTo(claim.channel().id());
        assertThat(messageRepository.processingToken).isEqualTo(claim.processingToken());
        assertThat(messageRepository.pollResult).isEqualTo(result);
        assertThat(messageRepository.nextPollAt).isEqualTo(NOW.plus(sourceAdapter.pollingInterval));
    }

    @Test
    void schedulesTemporaryFailureUsingRetryPolicyAndRetryAfter() {
        ChannelClaim claim = claim(ChannelOperation.POLL);
        Duration retryAfter = Duration.ofMinutes(2);
        SourceTemporaryException failure = new SourceTemporaryException("Telegram unavailable", retryAfter);
        sourceAdapter.failure = failure;

        channelProcessor.process(claim);

        assertThat(channelRepository.failedOperation).isEqualTo(ChannelOperation.POLL);
        assertThat(channelRepository.failedToken).isEqualTo(claim.processingToken());
        assertThat(channelRepository.nextAttemptAt).isEqualTo(NOW.plus(Duration.ofMinutes(4)));
        assertThat(channelRepository.error).isEqualTo(failure.getMessage());
    }

    @Test
    void stopsAutomaticSchedulingAfterPermanentFailure() {
        ChannelClaim claim = claim(ChannelOperation.ACCESS);
        SourcePermanentException failure = new SourcePermanentException("Invite expired");
        sourceAdapter.failure = failure;

        channelProcessor.process(claim);

        assertThat(channelRepository.failedOperation).isEqualTo(ChannelOperation.ACCESS);
        assertThat(channelRepository.failedToken).isEqualTo(claim.processingToken());
        assertThat(channelRepository.nextAttemptAt).isNull();
        assertThat(channelRepository.error).isEqualTo(failure.getMessage());
    }

    @Test
    void discardsStaleCompletionWithoutAnotherWrite() {
        ChannelClaim claim = claim(ChannelOperation.ACCESS);
        AccessResult result = new AccessResult(
            AccessState.GRANTED,
            "channel-1",
            JsonNodeFactory.instance.objectNode()
        );
        sourceAdapter.accessResult = result;
        channelRepository.completionAccepted = false;

        channelProcessor.process(claim);

        assertThat(channelRepository.completedAccess).isEqualTo(result);
        assertThat(channelRepository.failedOperation).isNull();
        assertThat(messageRepository.pollResult).isNull();
    }

    private ChannelClaim claim(ChannelOperation operation) {
        return new ChannelClaim(channel(), operation, UUID.randomUUID(), 3);
    }

    private ChannelSnapshot channel() {
        var empty = JsonNodeFactory.instance.objectNode();
        return new ChannelSnapshot(
            17,
            SourceType.TELEGRAM,
            "channel-1",
            URI.create("https://t.me/channel-1"),
            empty,
            AccessState.GRANTED,
            empty,
            SubscriptionState.ACTIVE,
            empty,
            empty
        );
    }

    private static class FakeSourceAdapterRegistry extends SourceAdapterRegistry {

        private final SourceAdapter sourceAdapter;

        FakeSourceAdapterRegistry(SourceAdapter sourceAdapter) {
            this.sourceAdapter = sourceAdapter;
        }

        @Override
        public SourceAdapter get(SourceType sourceType) {
            return sourceAdapter;
        }

    }

    private static class RecordingChannelRepository extends ChannelRepository {

        private boolean completionAccepted = true;
        private AccessResult completedAccess;
        private SubscriptionResult completedSubscription;
        private UUID completedToken;
        private ChannelOperation failedOperation;
        private UUID failedToken;
        private Instant nextAttemptAt;
        private String error;

        @Override
        public boolean completeAccess(long channelId, UUID token, AccessResult result) {
            completedAccess = result;
            completedToken = token;
            return completionAccepted;
        }

        @Override
        public boolean completeSubscription(long channelId, UUID token, SubscriptionResult result) {
            completedSubscription = result;
            completedToken = token;
            return completionAccepted;
        }

        @Override
        public boolean failAccess(long channelId, UUID token, Instant nextAttemptAt, String error) {
            return recordFailure(ChannelOperation.ACCESS, token, nextAttemptAt, error);
        }

        @Override
        public boolean failSubscription(long channelId, UUID token, Instant nextAttemptAt, String error) {
            return recordFailure(ChannelOperation.SUBSCRIPTION, token, nextAttemptAt, error);
        }

        @Override
        public boolean failPoll(long channelId, UUID token, Instant nextAttemptAt, String error) {
            return recordFailure(ChannelOperation.POLL, token, nextAttemptAt, error);
        }

        private boolean recordFailure(
            ChannelOperation operation,
            UUID token,
            Instant attemptAt,
            String diagnostic
        ) {
            failedOperation = operation;
            failedToken = token;
            nextAttemptAt = attemptAt;
            error = diagnostic;
            return completionAccepted;
        }

    }

    private static class RecordingMessageRepository extends MessageRepository {

        private long channelId;
        private UUID processingToken;
        private PollResult pollResult;
        private Instant nextPollAt;

        @Override
        public boolean ingestPoll(
            long channelId,
            UUID processingToken,
            PollResult result,
            Instant nextPollAt
        ) {
            this.channelId = channelId;
            this.processingToken = processingToken;
            this.pollResult = result;
            this.nextPollAt = nextPollAt;
            return true;
        }

    }

    private static class FakeSourceAdapter implements SourceAdapter {

        private AccessResult accessResult;
        private SubscriptionResult subscriptionResult;
        private PollResult pollResult;
        private Duration pollingInterval;
        private RuntimeException failure;

        @Override
        public SourceType type() {
            return SourceType.TELEGRAM;
        }

        @Override
        public AccessResult refreshAccess(ChannelSnapshot channel) {
            throwIfFailed();
            return accessResult;
        }

        @Override
        public SubscriptionResult refreshSubscription(ChannelSnapshot channel) {
            throwIfFailed();
            return subscriptionResult;
        }

        @Override
        public PollResult poll(ChannelSnapshot channel) {
            throwIfFailed();
            return pollResult;
        }

        @Override
        public Duration pollingInterval(ChannelSnapshot channel) {
            return pollingInterval;
        }

        private void throwIfFailed() {
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public ChannelResolution resolveChannel(URI sourceUri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Duration groupingThreshold() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Duration settlingDelay() {
            throw new UnsupportedOperationException();
        }

        @Override
        public GroupingRelation groupingRelation(SourceMessage previous, SourceMessage next) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void downloadMedia(ChannelSnapshot channel, MediaSnapshot media, Path destination) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void start(SourceEventSink sink) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void stop() {
            throw new UnsupportedOperationException();
        }

    }

}
