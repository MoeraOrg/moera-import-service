package org.moera.importer.application;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.moera.importer.config.WorkerProperties;
import org.moera.importer.model.AccessState;
import org.moera.importer.model.ChannelOperation;
import org.moera.importer.model.SourceType;
import org.moera.importer.model.SubscriptionState;
import org.moera.importer.observability.ImportMetrics;
import org.moera.importer.persistence.ChannelClaim;
import org.moera.importer.persistence.ChannelRepository;
import org.moera.importer.source.ChannelSnapshot;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class PollLeaseHeartbeatTest {

    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @AfterEach
    void shutdownExecutor() {
        executorService.shutdownNow();
    }

    @Test
    void renewsLeaseOnVirtualThread() throws Exception {
        RecordingChannelRepository channelRepository = new RecordingChannelRepository(1);
        channelRepository.results.add(true);
        PollLeaseHeartbeat heartbeat = heartbeat(channelRepository, Duration.ofMillis(90));

        try (PollLeaseHeartbeat.Guard ignored = heartbeat.start(claim())) {
            assertThat(channelRepository.calls.await(1, TimeUnit.SECONDS)).isTrue();
            awaitRenewal("renewed");
        }

        assertThat(channelRepository.virtualThread).isTrue();
        assertThat(renewalCount("renewed")).isEqualTo(1);
    }

    @Test
    void retriesTransientRenewalFailureBeforeExpiry() throws Exception {
        RecordingChannelRepository channelRepository = new RecordingChannelRepository(2);
        channelRepository.failures.add(new IllegalStateException("database unavailable"));
        channelRepository.results.add(true);
        PollLeaseHeartbeat heartbeat = heartbeat(channelRepository, Duration.ofMillis(300));

        try (PollLeaseHeartbeat.Guard ignored = heartbeat.start(claim())) {
            assertThat(channelRepository.calls.await(2, TimeUnit.SECONDS)).isTrue();
            awaitRenewal("renewed");
        }

        assertThat(renewalCount("failed")).isEqualTo(1);
        assertThat(renewalCount("renewed")).isEqualTo(1);
    }

    @Test
    void rejectedRenewalMarksLeaseLostAndInterruptsOwner() throws Exception {
        RecordingChannelRepository channelRepository = new RecordingChannelRepository(1);
        channelRepository.results.add(false);
        PollLeaseHeartbeat heartbeat = heartbeat(channelRepository, Duration.ofMillis(90));
        CountDownLatch ownerStarted = new CountDownLatch(1);

        Future<Boolean> owner = executorService.submit(() -> {
            try (PollLeaseHeartbeat.Guard guard = heartbeat.start(claim())) {
                ownerStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                    return false;
                } catch (InterruptedException e) {
                    return guard.leaseLost();
                }
            }
        });

        assertThat(ownerStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(owner.get(2, TimeUnit.SECONDS)).isTrue();
        assertThat(renewalCount("lost")).isEqualTo(1);
    }

    @Test
    void repeatedRenewalFailuresLoseLeaseAtLocalExpiry() throws Exception {
        RecordingChannelRepository channelRepository = new RecordingChannelRepository(2);
        channelRepository.defaultFailure = new IllegalStateException("database unavailable");
        PollLeaseHeartbeat heartbeat = heartbeat(channelRepository, Duration.ofMillis(120));

        Future<Boolean> owner = executorService.submit(() -> {
            try (PollLeaseHeartbeat.Guard guard = heartbeat.start(claim())) {
                try {
                    new CountDownLatch(1).await();
                    return false;
                } catch (InterruptedException e) {
                    return guard.leaseLost();
                }
            }
        });

        assertThat(owner.get(2, TimeUnit.SECONDS)).isTrue();
        assertThat(channelRepository.callCount).isGreaterThanOrEqualTo(2);
        assertThat(renewalCount("failed")).isGreaterThanOrEqualTo(2);
        assertThat(renewalCount("lost")).isEqualTo(1);
    }

    @Test
    void closeStopsFurtherRenewal() throws Exception {
        RecordingChannelRepository channelRepository = new RecordingChannelRepository(1);
        channelRepository.defaultResult = true;
        Duration lease = Duration.ofMillis(90);
        PollLeaseHeartbeat heartbeat = heartbeat(channelRepository, lease);

        PollLeaseHeartbeat.Guard guard = heartbeat.start(claim());
        assertThat(channelRepository.calls.await(1, TimeUnit.SECONDS)).isTrue();
        guard.close();
        int callsAfterClose = channelRepository.callCount;
        Thread.sleep(lease.dividedBy(3).multipliedBy(2));

        assertThat(channelRepository.callCount).isEqualTo(callsAfterClose);
    }

    private PollLeaseHeartbeat heartbeat(ChannelRepository channelRepository, Duration lease) {
        WorkerProperties workerProperties = new WorkerProperties();
        workerProperties.setChannelLease(lease);
        ImportMetrics importMetrics = new ImportMetrics();
        ReflectionTestUtils.setField(importMetrics, "meterRegistry", meterRegistry);
        PollLeaseHeartbeat heartbeat = new PollLeaseHeartbeat();
        ReflectionTestUtils.setField(heartbeat, "channelRepository", channelRepository);
        ReflectionTestUtils.setField(heartbeat, "clock", Clock.systemUTC());
        ReflectionTestUtils.setField(heartbeat, "executorService", executorService);
        ReflectionTestUtils.setField(heartbeat, "importMetrics", importMetrics);
        ReflectionTestUtils.setField(heartbeat, "workerProperties", workerProperties);
        return heartbeat;
    }

    private void awaitRenewal(String result) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (renewalCount(result) == 0 && System.nanoTime() < deadline) {
            Thread.sleep(Duration.ofMillis(5));
        }
    }

    private double renewalCount(String result) {
        var counter = meterRegistry.find("lease_renewals_total")
            .tag("result", result)
            .counter();
        return counter != null ? counter.count() : 0;
    }

    private ChannelClaim claim() {
        var empty = JsonNodeFactory.instance.objectNode();
        ChannelSnapshot channel = new ChannelSnapshot(
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
        return new ChannelClaim(channel, ChannelOperation.POLL, UUID.randomUUID(), 1);
    }

    private static class RecordingChannelRepository extends ChannelRepository {

        private final CountDownLatch calls;
        private final Queue<RuntimeException> failures = new ArrayDeque<>();
        private final Queue<Boolean> results = new ArrayDeque<>();
        private volatile int callCount;
        private volatile boolean virtualThread;
        private boolean defaultResult;
        private RuntimeException defaultFailure;

        RecordingChannelRepository(int expectedCalls) {
            calls = new CountDownLatch(expectedCalls);
        }

        @Override
        public synchronized boolean renewPollLease(long channelId, UUID token, Duration lease) {
            callCount++;
            virtualThread = Thread.currentThread().isVirtual();
            calls.countDown();
            if (!failures.isEmpty()) {
                throw failures.remove();
            }
            if (defaultFailure != null) {
                throw defaultFailure;
            }
            return results.isEmpty() ? defaultResult : results.remove();
        }

    }

}
