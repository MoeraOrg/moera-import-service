package org.moera.importer.scheduling;

import java.util.concurrent.ExecutorService;
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
    private ExecutorService executorService;

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
        mirrorRepository.findWithUnassignedMessages(workerProperties.getBatchSize())
            .forEach(mirrorId -> executorService.submit(() -> publicationBuilder.build(mirrorId)));
    }

}
