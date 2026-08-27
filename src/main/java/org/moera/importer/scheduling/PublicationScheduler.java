package org.moera.importer.scheduling;

import java.util.concurrent.ExecutorService;
import jakarta.inject.Inject;

import org.moera.importer.application.PublicationProcessor;
import org.moera.importer.config.WorkerProperties;
import org.moera.importer.persistence.PublicationRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PublicationScheduler {

    @Inject
    private ExecutorService executorService;

    @Inject
    private PublicationProcessor publicationProcessor;

    @Inject
    private PublicationRepository publicationRepository;

    @Inject
    private WorkerProperties workerProperties;

    @Scheduled(fixedDelayString = "${moera.workers.publication-schedule-interval:PT1S}")
    public void run() {
        if (!workerProperties.isSchedulingEnabled()) {
            return;
        }
        publicationRepository.claimReady(
            workerProperties.getBatchSize(), workerProperties.getPublicationLease()
        ).forEach(claim -> executorService.submit(() -> publicationProcessor.process(claim)));
    }

}
