package org.moera.importer.config;

import jakarta.inject.Inject;

import org.moera.importer.source.telegram.TelegramAdapterLifecycle;
import org.moera.importer.staging.StagingCleanup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@DependsOnDatabaseInitialization
public class StartupCoordinator implements SmartLifecycle {

    public enum Phase {
        STARTING,
        TDLIGHT_INITIALIZATION,
        STAGING_CLEANUP,
        READY,
        STOPPING,
        STOPPED
    }

    @Inject
    private StagingCleanup stagingCleanup;

    @Inject
    private ObjectProvider<TelegramAdapterLifecycle> telegramAdapterLifecycleProvider;

    @Inject
    private WorkerExecutorConfiguration workerExecutorConfiguration;

    @Inject
    private WorkerProperties workerProperties;

    @Inject
    private TelegramProperties telegramProperties;

    private volatile Phase phase = Phase.STARTING;
    private volatile boolean claimsEnabled;
    private volatile boolean running;

    @Override
    public synchronized void start() {
        running = true;
        continueStartup();
    }

    @Scheduled(fixedDelayString = "${moera.workers.startup-check-interval:PT1S}")
    public synchronized void maintain() {
        if (running && phase != Phase.READY) {
            continueStartup();
        }
    }

    @Scheduled(
        fixedDelayString = "${moera.storage.cleanup-interval:PT1H}",
        initialDelayString = "${moera.storage.cleanup-interval:PT1H}"
    )
    public synchronized void cleanStaging() {
        if (running && phase == Phase.READY) {
            stagingCleanup.clean();
        }
    }

    private void continueStartup() {
        if (telegramProperties.isLogin()) {
            phase = Phase.TDLIGHT_INITIALIZATION;
            return;
        }
        TelegramAdapterLifecycle telegramAdapterLifecycle = telegramAdapterLifecycleProvider.getIfAvailable();
        if (telegramAdapterLifecycle != null) {
            phase = Phase.TDLIGHT_INITIALIZATION;
            if (!telegramAdapterLifecycle.isRunning()) {
                telegramAdapterLifecycle.start();
            }
            if (!telegramAdapterLifecycle.isInitialized()) {
                return;
            }
        }
        phase = Phase.STAGING_CLEANUP;
        stagingCleanup.clean();
        claimsEnabled = workerProperties.isSchedulingEnabled();
        phase = Phase.READY;
    }

    @Override
    public synchronized void stop() {
        phase = Phase.STOPPING;
        claimsEnabled = false;
        TelegramAdapterLifecycle telegramAdapterLifecycle = telegramAdapterLifecycleProvider.getIfAvailable();
        if (telegramAdapterLifecycle != null && telegramAdapterLifecycle.isRunning()) {
            telegramAdapterLifecycle.stop();
        }
        workerExecutorConfiguration.shutdown(workerProperties.getShutdownGracePeriod());
        running = false;
        phase = Phase.STOPPED;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public boolean isReady() {
        return phase == Phase.READY;
    }

    public boolean claimsEnabled() {
        return claimsEnabled;
    }

    public Phase phase() {
        return phase;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

}
