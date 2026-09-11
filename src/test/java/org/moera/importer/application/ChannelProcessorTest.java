package org.moera.importer.application;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moera.importer.model.AccessState;
import org.moera.importer.model.ChannelOperation;
import org.moera.importer.model.SourceType;
import org.moera.importer.model.SubscriptionState;
import org.moera.importer.observability.ImportMetrics;
import org.moera.importer.persistence.ChannelClaim;
import org.moera.importer.persistence.ChannelRepository;
import org.moera.importer.persistence.MessageRepository;
import org.moera.importer.source.AccessResult;
import org.moera.importer.source.ChannelResolution;
import org.moera.importer.source.ChannelSnapshot;
import org.moera.importer.source.GroupingRelation;
import org.moera.importer.source.InitialImportMode;
import org.moera.importer.source.MediaSnapshot;
import org.moera.importer.source.PollResult;
import org.moera.importer.source.SourceAdapter;
import org.moera.importer.source.SourceAdapterRegistry;
import org.moera.importer.source.SourceEventSink;
import org.moera.importer.source.SourceMessage;
import org.moera.importer.source.SourcePermanentException;
import org.moera.importer.source.SourceTemporaryException;
import org.moera.importer.source.SubscriptionResult;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChannelProcessorTest {

    private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");

    private ChannelProcessor channelProcessor;
    private RecordingChannelRepository channelRepository;
    private RecordingMessageRepository messageRepository;
    private FakeSourceAdapter sourceAdapter;
    private SimpleMeterRegistry meterRegistry;
    private RecordingPollLeaseHeartbeat pollLeaseHeartbeat;

    @BeforeEach
    void configureProcessor() {
        channelProcessor = new ChannelProcessor();
        channelRepository = new RecordingChannelRepository();
        messageRepository = new RecordingMessageRepository();
        sourceAdapter = new FakeSourceAdapter();
        sourceAdapter.pollingInterval = Duration.ofMinutes(5);
        pollLeaseHeartbeat = new RecordingPollLeaseHeartbeat();
        messageRepository.pollLeaseHeartbeat = pollLeaseHeartbeat;
        meterRegistry = new SimpleMeterRegistry();
        ImportMetrics importMetrics = new ImportMetrics();
        ReflectionTestUtils.setField(importMetrics, "meterRegistry", meterRegistry);
        RetryPolicy retryPolicy = new RetryPolicy(
            Duration.ofMinutes(1),
            Duration.ofHours(1),
            0,
            () -> 0.5
        );
        ReflectionTestUtils.setField(channelProcessor, "channelRepository", channelRepository);
        ReflectionTestUtils.setField(channelProcessor, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(channelProcessor, "messageRepository", messageRepository);
        ReflectionTestUtils.setField(channelProcessor, "importMetrics", importMetrics);
        ReflectionTestUtils.setField(channelProcessor, "pollLeaseHeartbeat", pollLeaseHeartbeat);
        ReflectionTestUtils.setField(channelProcessor, "profileSynchronizer", mock(ProfileSynchronizer.class));
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
        assertThat(meterRegistry.get("source_operations_total")
            .tags("source", "telegram", "operation", "access", "result", "success")
            .counter().count()).isEqualTo(1);
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
        assertThat(messageRepository.heartbeatOpenAtIngest).isTrue();
        assertThat(pollLeaseHeartbeat.guard.closed).isTrue();
    }

    @Test
    void reportsStaleClaimWhenPollIngestRejectsToken() {
        messageRepository.pollAccepted = false;
        sourceAdapter.pollResult = new PollResult(
            List.of(),
            JsonNodeFactory.instance.objectNode().put("messageId", 42)
        );

        channelProcessor.process(claim(ChannelOperation.POLL));

        assertThat(meterRegistry.get("source_operations_total")
            .tags("source", "telegram", "operation", "poll", "result", "stale_claim")
            .counter().count()).isEqualTo(1);
        assertThat(meterRegistry.find("source_operations_total")
            .tags("source", "telegram", "operation", "poll", "result", "success")
            .counter()).isNull();
        assertThat(messageRepository.heartbeatOpenAtIngest).isTrue();
        assertThat(pollLeaseHeartbeat.guard.closed).isTrue();
    }

    @Test
    void schedulesTemporaryFailureUsingRetryPolicyAndRetryAfter() {
        ChannelClaim claim = claim(ChannelOperation.POLL);
        Duration retryAfter = Duration.ofMinutes(2);
        SourceTemporaryException failure = new SourceTemporaryException("Telegram unavailable", retryAfter);
        sourceAdapter.failure = failure;

        Logger logger = (Logger) LoggerFactory.getLogger(ChannelProcessor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            channelProcessor.process(claim);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(channelRepository.failedOperation).isEqualTo(ChannelOperation.POLL);
        assertThat(channelRepository.failedToken).isEqualTo(claim.processingToken());
        assertThat(channelRepository.nextAttemptAt).isEqualTo(NOW.plus(Duration.ofMinutes(4)));
        assertThat(channelRepository.error).isEqualTo(failure.getMessage());
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
            .contains(
                "operation=POLL",
                "channel_id=17",
                "attempt=3",
                "result=temporary_failure",
                "error=Telegram unavailable"
            ));
    }

    @Test
    void stopsSchedulingAfterSameTemporaryFailureTypeForOneDay() {
        var cause = new IllegalStateException("network down");
        sourceAdapter.failure = new SourceTemporaryException("Telegram unavailable", cause);
        ChannelClaim claim = new ChannelClaim(
            channel(),
            ChannelOperation.POLL,
            UUID.randomUUID(),
            3,
            new FailureSeries(cause.getClass().getName(), NOW.minus(Duration.ofDays(1)))
        );

        channelProcessor.process(claim);

        assertThat(channelRepository.failedOperation).isEqualTo(ChannelOperation.POLL);
        assertThat(channelRepository.nextAttemptAt).isNull();
        assertThat(meterRegistry.get("source_operations_total")
            .tags("source", "telegram", "operation", "poll", "result", "permanent_failure")
            .counter().count()).isEqualTo(1);
    }

    @Test
    void reportsStaleClaimWhenTemporaryFailureCompletionRejectsToken() {
        sourceAdapter.failure = new SourceTemporaryException("Telegram unavailable");
        channelRepository.completionAccepted = false;

        channelProcessor.process(claim(ChannelOperation.ACCESS));

        assertThat(meterRegistry.get("source_operations_total")
            .tags("source", "telegram", "operation", "access", "result", "stale_claim")
            .counter().count()).isEqualTo(1);
        assertThat(meterRegistry.find("source_operations_total")
            .tags("source", "telegram", "operation", "access", "result", "temporary_failure")
            .counter()).isNull();
    }

    @Test
    void doesNotSchedulePollFailureAfterHeartbeatLosesLease() {
        SourceTemporaryException failure = new SourceTemporaryException("Telegram unavailable");
        sourceAdapter.failure = failure;
        pollLeaseHeartbeat.lostOnStart = true;

        channelProcessor.process(claim(ChannelOperation.POLL));

        assertThat(channelRepository.failedOperation).isNull();
        assertThat(meterRegistry.get("source_operations_total")
            .tags("source", "telegram", "operation", "poll", "result", "stale_claim")
            .counter().count()).isEqualTo(1);
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
        assertThat(meterRegistry.get("source_operations_total")
            .tags("source", "telegram", "operation", "access", "result", "stale_claim")
            .counter().count()).isEqualTo(1);
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
        public boolean failAccess(
            long channelId,
            UUID token,
            Instant nextAttemptAt,
            String error,
            FailureSeries failureSeries
        ) {
            return recordFailure(ChannelOperation.ACCESS, token, nextAttemptAt, error);
        }

        @Override
        public boolean failSubscription(
            long channelId,
            UUID token,
            Instant nextAttemptAt,
            String error,
            FailureSeries failureSeries
        ) {
            return recordFailure(ChannelOperation.SUBSCRIPTION, token, nextAttemptAt, error);
        }

        @Override
        public boolean failPoll(
            long channelId,
            UUID token,
            Instant nextAttemptAt,
            String error,
            FailureSeries failureSeries
        ) {
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
        private RecordingPollLeaseHeartbeat pollLeaseHeartbeat;
        private boolean pollAccepted = true;
        private boolean heartbeatOpenAtIngest;

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
            heartbeatOpenAtIngest = pollLeaseHeartbeat != null
                && pollLeaseHeartbeat.guard != null
                && !pollLeaseHeartbeat.guard.closed;
            return pollAccepted;
        }

    }

    private static class RecordingPollLeaseHeartbeat extends PollLeaseHeartbeat {

        private RecordingGuard guard;
        private boolean lostOnStart;

        @Override
        public Guard start(ChannelClaim claim) {
            guard = new RecordingGuard();
            guard.lost = lostOnStart;
            return guard;
        }

        private static class RecordingGuard implements Guard {

            private boolean lost;
            private boolean closed;

            @Override
            public boolean leaseLost() {
                return lost;
            }

            @Override
            public void close() {
                closed = true;
            }

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
        public ChannelResolution resolveChannel(URI sourceUri, InitialImportMode initialImportMode) {
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
