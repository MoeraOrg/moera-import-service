package org.moera.importer.scheduling;

import jakarta.inject.Inject;

import org.moera.importer.application.PublicationMediaProcessor;
import org.moera.importer.config.WorkerProperties;
import org.moera.importer.config.StartupCoordinator;
import org.moera.importer.persistence.PublicationMediaClaim;
import org.moera.importer.persistence.PublicationMediaRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PublicationMediaScheduler {

    @Inject
    private WorkerDispatcher workerDispatcher;

    @Inject
    private PublicationMediaProcessor publicationMediaProcessor;

    @Inject
    private PublicationMediaRepository publicationMediaRepository;

    @Inject
    private WorkerProperties workerProperties;

    @Inject
    private StartupCoordinator startupCoordinator;

    @Scheduled(fixedDelayString = "${moera.workers.publication-media-schedule-interval:PT1S}")
    public void run() {
        if (!startupCoordinator.claimsEnabled()) {
            return;
        }
        try (WorkerDispatcher.Reservation reservation = workerDispatcher.reserve(
            WorkerWorkflow.PUBLICATION_MEDIA, workerProperties.getBatchSize()
        )) {
            if (reservation.capacity() == 0) {
                return;
            }
            publicationMediaRepository.claimPending(
                reservation.capacity(), workerProperties.getPublicationMediaLease()
            ).forEach(claim -> reservation.dispatch(() -> process(claim)));
        }
    }

    private void process(PublicationMediaClaim claim) {
        try {
            publicationMediaProcessor.process(claim);
        } catch (RuntimeException ignored) {
            // PublicationMediaProcessor has already logged the failure with the claim context and stack trace.
        }
    }

}
