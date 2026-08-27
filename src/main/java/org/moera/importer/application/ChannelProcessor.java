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
    private RetryPolicy retryPolicy;

    @Inject
    private SourceAdapterRegistry sourceAdapterRegistry;

    public void process(ChannelClaim claim) {
        Timer.Sample sample = importMetrics.startTimer();
        String result = "unexpected_failure";
        SourceAdapter sourceAdapter = sourceAdapterRegistry.get(claim.channel().sourceType());
        try {
            switch (claim.operation()) {
                case ACCESS -> refreshAccess(claim, sourceAdapter);
                case SUBSCRIPTION -> refreshSubscription(claim, sourceAdapter);
                case POLL -> poll(claim, sourceAdapter);
            }
            result = "success";
        } catch (SourceTemporaryException e) {
            result = "temporary_failure";
            temporaryFailure(claim, e);
        } catch (SourcePermanentException e) {
            result = "permanent_failure";
            permanentFailure(claim, e);
        } finally {
            importMetrics.sourceOperation(claim.channel().sourceType(), claim.operation(), result, sample);
            log.atInfo()
                .addKeyValue("operation", claim.operation())
                .addKeyValue("channel_id", claim.channel().id())
                .addKeyValue("processing_token", claim.processingToken())
                .addKeyValue("attempt", claim.attempts())
                .addKeyValue("result", result)
                .log("Source operation finished");
        }
    }

    private void refreshAccess(ChannelClaim claim, SourceAdapter sourceAdapter) {
        AccessResult result = sourceAdapter.refreshAccess(claim.channel());
        if (claim.channel().sourceId() != null
            && result.resolvedSourceId() != null
            && !claim.channel().sourceId().equals(result.resolvedSourceId())) {
            throw new SourcePermanentException("Resolved source identity conflicts with the stored identity");
        }
        channelRepository.completeAccess(claim.channel().id(), claim.processingToken(), result);
    }

    private void refreshSubscription(ChannelClaim claim, SourceAdapter sourceAdapter) {
        var result = sourceAdapter.refreshSubscription(claim.channel());
        channelRepository.completeSubscription(claim.channel().id(), claim.processingToken(), result);
    }

    private void poll(ChannelClaim claim, SourceAdapter sourceAdapter) {
        var result = sourceAdapter.poll(claim.channel());
        importMetrics.pollMessagesFound(result.messages().size());
        Duration pollingInterval = sourceAdapter.pollingInterval(claim.channel());
        Instant nextPollAt = clock.instant().plus(pollingInterval);
        messageRepository.ingestPoll(claim.channel().id(), claim.processingToken(), result, nextPollAt);
    }

    private void temporaryFailure(ChannelClaim claim, SourceTemporaryException exception) {
        Duration delay = retryPolicy.delay(claim.attempts(), exception.retryAfter());
        fail(claim, clock.instant().plus(delay), diagnostic(exception));
    }

    private void permanentFailure(ChannelClaim claim, SourcePermanentException exception) {
        fail(claim, null, diagnostic(exception));
    }

    private void fail(ChannelClaim claim, Instant nextAttemptAt, String error) {
        switch (claim.operation()) {
            case ACCESS -> channelRepository.failAccess(
                claim.channel().id(), claim.processingToken(), nextAttemptAt, error
            );
            case SUBSCRIPTION -> channelRepository.failSubscription(
                claim.channel().id(), claim.processingToken(), nextAttemptAt, error
            );
            case POLL -> channelRepository.failPoll(
                claim.channel().id(), claim.processingToken(), nextAttemptAt, error
            );
        }
    }

    private String diagnostic(RuntimeException exception) {
        return exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
    }

}
