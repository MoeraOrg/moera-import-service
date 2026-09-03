package org.moera.importer.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.inject.Inject;

import org.moera.importer.config.WorkerProperties;
import org.moera.importer.observability.ImportMetrics;
import org.moera.importer.persistence.ChannelClaim;
import org.moera.importer.persistence.ChannelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PollLeaseHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(PollLeaseHeartbeat.class);

    @Inject
    private ChannelRepository channelRepository;

    @Inject
    private Clock clock;

    @Inject
    private ExecutorService executorService;

    @Inject
    private ImportMetrics importMetrics;

    @Inject
    private WorkerProperties workerProperties;

    public Guard start(ChannelClaim claim) {
        Duration lease = workerProperties.getChannelLease();
        Duration interval = lease.dividedBy(3);
        if (interval.isZero()) {
            interval = Duration.ofNanos(1);
        }
        GuardImpl guard = new GuardImpl(
            claim,
            Thread.currentThread(),
            lease,
            interval,
            clock.instant().plus(lease)
        );
        guard.attach(executorService.submit(guard::run));
        return guard;
    }

    public interface Guard extends AutoCloseable {

        boolean leaseLost();

        @Override
        void close();

    }

    private final class GuardImpl implements Guard {

        private final ChannelClaim claim;
        private final Thread owner;
        private final Duration lease;
        private final Duration interval;
        private final AtomicReference<Future<?>> heartbeatTask = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean lost = new AtomicBoolean();
        private volatile Instant localExpiry;

        private GuardImpl(
            ChannelClaim claim,
            Thread owner,
            Duration lease,
            Duration interval,
            Instant localExpiry
        ) {
            this.claim = claim;
            this.owner = owner;
            this.lease = lease;
            this.interval = interval;
            this.localExpiry = localExpiry;
        }

        private void attach(Future<?> task) {
            heartbeatTask.set(task);
            if (closed.get()) {
                task.cancel(true);
            }
        }

        private void run() {
            while (!closed.get() && !lost.get()) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (closed.get() || lost.get()) {
                    return;
                }
                if (!clock.instant().isBefore(localExpiry)) {
                    markLost();
                    return;
                }
                renew();
            }
        }

        private void renew() {
            try {
                boolean renewed = channelRepository.renewPollLease(
                    claim.channel().id(), claim.processingToken(), lease
                );
                if (closed.get()) {
                    return;
                }
                if (!renewed) {
                    markLost();
                    return;
                }
                localExpiry = clock.instant().plus(lease);
                record("renewed");
            } catch (RuntimeException e) {
                if (closed.get()) {
                    return;
                }
                record("failed", diagnostic(e));
                if (!clock.instant().isBefore(localExpiry)) {
                    markLost();
                }
            }
        }

        private void markLost() {
            if (lost.compareAndSet(false, true)) {
                record("lost");
                owner.interrupt();
            }
        }

        private void record(String result) {
            record(result, null);
        }

        private void record(String result, String error) {
            importMetrics.leaseRenewal(result);
            if (error == null) {
                log.info(
                    "Channel poll lease renewal finished: operation=channel_poll_lease_renewal, channel_id={}, "
                        + "processing_token={}, result={}",
                    claim.channel().id(), claim.processingToken(), result
                );
            } else {
                log.info(
                    "Channel poll lease renewal finished: operation=channel_poll_lease_renewal, channel_id={}, "
                        + "processing_token={}, result={}, error={}",
                    claim.channel().id(), claim.processingToken(), result, error
                );
            }
        }

        private String diagnostic(RuntimeException exception) {
            return exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
        }

        @Override
        public boolean leaseLost() {
            return lost.get();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                Future<?> task = heartbeatTask.get();
                if (task != null) {
                    task.cancel(true);
                }
            }
        }

    }

}
