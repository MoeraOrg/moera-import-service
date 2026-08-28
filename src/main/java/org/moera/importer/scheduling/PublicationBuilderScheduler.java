package org.moera.importer.scheduling;

import jakarta.inject.Inject;

import org.moera.importer.application.PublicationBuilder;
import org.moera.importer.config.WorkerProperties;
import org.moera.importer.config.StartupCoordinator;
import org.moera.importer.persistence.MirrorRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PublicationBuilderScheduler {

    @Inject
    private WorkerDispatcher workerDispatcher;

    @Inject
    private MirrorRepository mirrorRepository;

    @Inject
    private PublicationBuilder publicationBuilder;

    @Inject
    private WorkerProperties workerProperties;

    @Inject
    private StartupCoordinator startupCoordinator;

    @Scheduled(fixedDelayString = "${moera.workers.publication-builder-schedule-interval:PT1S}")
    public void run() {
        if (!startupCoordinator.claimsEnabled()) {
            return;
        }
        try (WorkerDispatcher.Reservation reservation = workerDispatcher.reserve(
            WorkerWorkflow.PUBLICATION_BUILDER, workerProperties.getBatchSize()
        )) {
            if (reservation.capacity() == 0) {
                return;
            }
            mirrorRepository.findWithUnassignedMessages(reservation.capacity())
                .forEach(mirrorId -> reservation.dispatch(() -> publicationBuilder.build(mirrorId)));
        }
    }

}
