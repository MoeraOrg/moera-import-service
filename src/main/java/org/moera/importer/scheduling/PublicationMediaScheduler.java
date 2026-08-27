package org.moera.importer.scheduling;

import java.util.concurrent.ExecutorService;
import jakarta.inject.Inject;

import org.moera.importer.application.PublicationMediaProcessor;
import org.moera.importer.config.WorkerProperties;
import org.moera.importer.config.StartupCoordinator;
import org.moera.importer.persistence.PublicationMediaRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PublicationMediaScheduler {

    @Inject
    private ExecutorService executorService;

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
        publicationMediaRepository.claimPending(
            workerProperties.getBatchSize(), workerProperties.getPublicationMediaLease()
        ).forEach(claim -> executorService.submit(() -> publicationMediaProcessor.process(claim)));
    }

}
