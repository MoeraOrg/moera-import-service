package org.moera.importer.scheduling;

import java.util.List;
import java.util.concurrent.ExecutorService;
import jakarta.inject.Inject;

import org.moera.importer.application.ChannelProcessor;
import org.moera.importer.config.WorkerProperties;
import org.moera.importer.persistence.ChannelClaim;
import org.moera.importer.persistence.ChannelRepository;
import org.moera.importer.source.telegram.TelegramRuntime;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ChannelScheduler {

    @Inject
    private ChannelProcessor channelProcessor;

    @Inject
    private ChannelRepository channelRepository;

    @Inject
    private ExecutorService executorService;

    @Inject
    private WorkerProperties workerProperties;

    @Inject
    private TelegramRuntime telegramRuntime;

    @Scheduled(fixedDelayString = "${moera.workers.channel-schedule-interval:PT1S}")
    public void run() {
        if (!workerProperties.isSchedulingEnabled() || !telegramRuntime.channelWorkReady()) {
            return;
        }
        int remaining = workerProperties.getBatchSize();
        List<ChannelClaim> accessClaims = channelRepository.claimAccess(
            remaining, workerProperties.getChannelLease()
        );
        dispatch(accessClaims);
        remaining -= accessClaims.size();

        List<ChannelClaim> subscriptionClaims = channelRepository.claimSubscriptions(
            remaining, workerProperties.getChannelLease()
        );
        dispatch(subscriptionClaims);
        remaining -= subscriptionClaims.size();

        List<ChannelClaim> pollClaims = channelRepository.claimPolls(
            remaining, workerProperties.getChannelLease()
        );
        dispatch(pollClaims);
    }

    private void dispatch(List<ChannelClaim> claims) {
        claims.forEach(claim -> executorService.submit(() -> channelProcessor.process(claim)));
    }

}
