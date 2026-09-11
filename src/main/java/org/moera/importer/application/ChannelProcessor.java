package org.moera.importer.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.Timer;
import org.moera.importer.observability.ImportMetrics;
import org.moera.importer.persistence.ChannelClaim;
import org.moera.importer.persistence.ChannelRepository;
import org.moera.importer.persistence.MessageRepository;
import org.moera.importer.source.AccessResult;
import org.moera.importer.source.PollResult;
import org.moera.importer.source.SourceAdapter;
import org.moera.importer.source.SourceAdapterRegistry;
import org.moera.importer.source.SourcePermanentException;
import org.moera.importer.source.SourceTemporaryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ChannelProcessor {

    private static final Logger log = LoggerFactory.getLogger(ChannelProcessor.class);

    @Inject
    private ChannelRepository channelRepository;

    @Inject
    private Clock clock;

    @Inject
    private MessageRepository messageRepository;

    @Inject
    private ImportMetrics importMetrics;

    @Inject
    private PollLeaseHeartbeat pollLeaseHeartbeat;

    @Inject
    private ProfileSynchronizer profileSynchronizer;

    @Inject
    private RetryPolicy retryPolicy;

    @Inject
    private SourceAdapterRegistry sourceAdapterRegistry;

    public void process(ChannelClaim claim) {
        Timer.Sample sample = importMetrics.startTimer();
        String result = "unexpected_failure";
        String error = null;
        SourceAdapter sourceAdapter = sourceAdapterRegistry.get(claim.channel().sourceType());
        try {
            boolean completed = switch (claim.operation()) {
                case ACCESS -> refreshAccess(claim, sourceAdapter);
                case SUBSCRIPTION -> refreshSubscription(claim, sourceAdapter);
                case POLL -> poll(claim, sourceAdapter);
            };
            result = completed ? "success" : "stale_claim";
        } catch (SourceTemporaryException e) {
            error = diagnostic(e);
            result = temporaryFailure(claim, e);
        } catch (SourcePermanentException e) {
            error = diagnostic(e);
            result = permanentFailure(claim, e) ? "permanent_failure" : "stale_claim";
        } finally {
            importMetrics.sourceOperation(claim.channel().sourceType(), claim.operation(), result, sample);
            if (error == null) {
                log.info(
                    "Source operation finished: operation={}, channel_id={}, processing_token={}, attempt={}, "
                        + "result={}",
                    claim.operation(), claim.channel().id(), claim.processingToken(), claim.attempts(), result
                );
            } else {
                log.info(
                    "Source operation finished: operation={}, channel_id={}, processing_token={}, attempt={}, "
                        + "result={}, error={}",
                    claim.operation(), claim.channel().id(), claim.processingToken(), claim.attempts(), result, error
                );
            }
        }
    }

    private boolean refreshAccess(ChannelClaim claim, SourceAdapter sourceAdapter) {
        AccessResult result = sourceAdapter.refreshAccess(claim.channel());
        if (claim.channel().sourceId() != null
            && result.resolvedSourceId() != null
            && !claim.channel().sourceId().equals(result.resolvedSourceId())) {
            throw new SourcePermanentException("Resolved source identity conflicts with the stored identity");
        }
        boolean completed = channelRepository.completeAccess(claim.channel().id(), claim.processingToken(), result);
        if (completed) {
            profileSynchronizer.synchronize(claim.channel().id());
        }
        return completed;
    }

    private boolean refreshSubscription(ChannelClaim claim, SourceAdapter sourceAdapter) {
        var result = sourceAdapter.refreshSubscription(claim.channel());
        return channelRepository.completeSubscription(claim.channel().id(), claim.processingToken(), result);
    }

    private boolean poll(ChannelClaim claim, SourceAdapter sourceAdapter) {
        boolean completed;
        try (PollLeaseHeartbeat.Guard guard = pollLeaseHeartbeat.start(claim)) {
            PollResult result;
            try {
                result = sourceAdapter.poll(claim.channel());
            } catch (RuntimeException e) {
                if (guard.leaseLost()) {
                    return false;
                }
                throw e;
            }
            if (guard.leaseLost()) {
                return false;
            }
            importMetrics.pollMessagesFound(result.messages().size());
            Duration pollingInterval = sourceAdapter.pollingInterval(claim.channel());
            Instant nextPollAt = clock.instant().plus(pollingInterval);
            completed = messageRepository.ingestPoll(
                claim.channel().id(), claim.processingToken(), result, nextPollAt
            );
        }
        if (completed) {
            profileSynchronizer.synchronize(claim.channel().id());
        }
        return completed;
    }

    private String temporaryFailure(ChannelClaim claim, SourceTemporaryException exception) {
        RetryDecision decision = retryPolicy.failure(
            claim.attempts(),
            exception.retryAfter(),
            claim.failureSeries(),
            exception,
            clock.instant()
        );
        boolean accepted = fail(claim, decision.nextAttemptAt(), diagnostic(exception), decision.series());
        if (!accepted) {
            return "stale_claim";
        }
        return decision.fatal() ? "permanent_failure" : "temporary_failure";
    }

    private boolean permanentFailure(ChannelClaim claim, SourcePermanentException exception) {
        return fail(claim, null, diagnostic(exception), null);
    }

    private boolean fail(ChannelClaim claim, Instant nextAttemptAt, String error, FailureSeries failureSeries) {
        return switch (claim.operation()) {
            case ACCESS -> channelRepository.failAccess(
                claim.channel().id(), claim.processingToken(), nextAttemptAt, error, failureSeries
            );
            case SUBSCRIPTION -> channelRepository.failSubscription(
                claim.channel().id(), claim.processingToken(), nextAttemptAt, error, failureSeries
            );
            case POLL -> channelRepository.failPoll(
                claim.channel().id(), claim.processingToken(), nextAttemptAt, error, failureSeries
            );
        };
    }

    private String diagnostic(RuntimeException exception) {
        return exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
    }

}
