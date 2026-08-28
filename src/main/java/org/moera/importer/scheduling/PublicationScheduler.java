package org.moera.importer.scheduling;

import jakarta.inject.Inject;

import org.moera.importer.application.PublicationProcessor;
import org.moera.importer.config.WorkerProperties;
import org.moera.importer.config.StartupCoordinator;
import org.moera.importer.persistence.PublicationRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PublicationScheduler {

    @Inject
    private WorkerDispatcher workerDispatcher;

    @Inject
    private PublicationProcessor publicationProcessor;

    @Inject
    private PublicationRepository publicationRepository;

    @Inject
    private WorkerProperties workerProperties;

    @Inject
    private StartupCoordinator startupCoordinator;

    @Scheduled(fixedDelayString = "${moera.workers.publication-schedule-interval:PT1S}")
    public void run() {
        if (!startupCoordinator.claimsEnabled()) {
            return;
        }
        try (WorkerDispatcher.Reservation reservation = workerDispatcher.reserve(
            WorkerWorkflow.PUBLICATION, workerProperties.getBatchSize()
        )) {
            if (reservation.capacity() == 0) {
                return;
            }
            publicationRepository.claimReady(
                reservation.capacity(), workerProperties.getPublicationLease()
            ).forEach(claim -> reservation.dispatch(() -> publicationProcessor.process(claim)));
        }
    }

}
