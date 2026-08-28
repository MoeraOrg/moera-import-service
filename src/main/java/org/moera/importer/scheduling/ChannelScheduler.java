package org.moera.importer.scheduling;

import java.util.List;
import jakarta.inject.Inject;

import org.moera.importer.application.ChannelProcessor;
import org.moera.importer.config.WorkerProperties;
import org.moera.importer.config.StartupCoordinator;
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
    private WorkerDispatcher workerDispatcher;

    @Inject
    private WorkerProperties workerProperties;

    @Inject
    private TelegramRuntime telegramRuntime;

    @Inject
    private StartupCoordinator startupCoordinator;

    @Scheduled(fixedDelayString = "${moera.workers.channel-schedule-interval:PT1S}")
    public void run() {
        if (!startupCoordinator.claimsEnabled() || !telegramRuntime.channelWorkReady()) {
            return;
        }
        try (WorkerDispatcher.Reservation reservation = workerDispatcher.reserve(
            WorkerWorkflow.CHANNEL, workerProperties.getBatchSize()
        )) {
            int remaining = reservation.capacity();
            if (remaining == 0) {
                return;
            }
            List<ChannelClaim> accessClaims = channelRepository.claimAccess(
                remaining, workerProperties.getChannelLease()
            );
            dispatch(accessClaims, reservation);
            remaining -= accessClaims.size();

            if (remaining == 0) {
                return;
            }
            List<ChannelClaim> subscriptionClaims = channelRepository.claimSubscriptions(
                remaining, workerProperties.getChannelLease()
            );
            dispatch(subscriptionClaims, reservation);
            remaining -= subscriptionClaims.size();

            if (remaining == 0) {
                return;
            }
            List<ChannelClaim> pollClaims = channelRepository.claimPolls(
                remaining, workerProperties.getChannelLease()
            );
            dispatch(pollClaims, reservation);
        }
    }

    private void dispatch(List<ChannelClaim> claims, WorkerDispatcher.Reservation reservation) {
        claims.forEach(claim -> reservation.dispatch(() -> channelProcessor.process(claim)));
    }

}
