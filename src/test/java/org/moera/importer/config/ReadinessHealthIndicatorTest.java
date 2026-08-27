package org.moera.importer.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.moera.importer.source.SourceEventSink;
import org.moera.importer.source.telegram.TelegramAdapterLifecycle;
import org.moera.importer.source.telegram.TelegramClient;
import org.moera.importer.source.telegram.TelegramSourceAdapter;
import org.moera.importer.staging.StagingCleanup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Status;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

class ReadinessHealthIndicatorTest {

    @Test
    void readinessStaysDownUntilTdlightAndStartupCleanupComplete() {
        TelegramSourceAdapter telegramSourceAdapter = mock(TelegramSourceAdapter.class);
        TelegramClient telegramClient = mock(TelegramClient.class);
        when(telegramClient.awaitReady(Duration.ofSeconds(3))).thenReturn(false);
        when(telegramClient.isReady()).thenReturn(false, true);
        TelegramProperties telegramProperties = new TelegramProperties();
        telegramProperties.setReadinessTimeout(Duration.ofSeconds(3));
        TelegramAdapterLifecycle telegramAdapterLifecycle = new TelegramAdapterLifecycle(
            telegramSourceAdapter, telegramClient, mock(SourceEventSink.class), telegramProperties
        );
        RecordingStagingCleanup stagingCleanup = new RecordingStagingCleanup();
        StartupCoordinator startupCoordinator = coordinator(telegramAdapterLifecycle, stagingCleanup);
        ReadinessHealthIndicator healthIndicator = new ReadinessHealthIndicator();
        ReflectionTestUtils.setField(healthIndicator, "startupCoordinator", startupCoordinator);

        assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.DOWN);

        startupCoordinator.start();

        assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(startupCoordinator.claimsEnabled()).isFalse();
        assertThat(stagingCleanup.runs).isZero();

        startupCoordinator.maintain();

        assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(startupCoordinator.claimsEnabled()).isTrue();
        assertThat(stagingCleanup.runs).isEqualTo(1);
    }

    @Test
    void readinessDoesNotDependOnMoeraAvailability() {
        RecordingStagingCleanup stagingCleanup = new RecordingStagingCleanup();
        StartupCoordinator startupCoordinator = coordinator(null, stagingCleanup);
        ReadinessHealthIndicator healthIndicator = new ReadinessHealthIndicator();
        ReflectionTestUtils.setField(healthIndicator, "startupCoordinator", startupCoordinator);

        startupCoordinator.start();

        assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void sourceAdapterStartsOnlyAfterTdlightIsReady() {
        TelegramSourceAdapter telegramSourceAdapter = mock(TelegramSourceAdapter.class);
        TelegramClient telegramClient = mock(TelegramClient.class);
        when(telegramClient.awaitReady(Duration.ofSeconds(3))).thenReturn(true);
        TelegramProperties telegramProperties = new TelegramProperties();
        telegramProperties.setReadinessTimeout(Duration.ofSeconds(3));
        SourceEventSink sourceEventSink = mock(SourceEventSink.class);
        TelegramAdapterLifecycle telegramAdapterLifecycle = new TelegramAdapterLifecycle(
            telegramSourceAdapter, telegramClient, sourceEventSink, telegramProperties
        );

        telegramAdapterLifecycle.start();

        var order = inOrder(telegramClient, telegramSourceAdapter);
        order.verify(telegramClient).awaitReady(Duration.ofSeconds(3));
        order.verify(telegramSourceAdapter).start(sourceEventSink);
    }

    @Test
    void dedicatedLoginModeNeverEnablesDaemonSchedulers() {
        StartupCoordinator startupCoordinator = coordinator(null, new RecordingStagingCleanup());
        TelegramProperties telegramProperties = new TelegramProperties();
        telegramProperties.setEnabled(true);
        telegramProperties.setLogin(true);
        ReflectionTestUtils.setField(startupCoordinator, "telegramProperties", telegramProperties);

        startupCoordinator.start();

        assertThat(startupCoordinator.isReady()).isFalse();
        assertThat(startupCoordinator.claimsEnabled()).isFalse();
    }

    @Test
    void shutdownStopsClaimsAndSourceIntakeBeforeWaitingForWorkers() {
        List<String> events = new ArrayList<>();
        TelegramSourceAdapter telegramSourceAdapter = mock(TelegramSourceAdapter.class);
        TelegramClient telegramClient = mock(TelegramClient.class);
        when(telegramClient.awaitReady(Duration.ofSeconds(3))).thenReturn(true);
        TelegramProperties telegramProperties = new TelegramProperties();
        telegramProperties.setReadinessTimeout(Duration.ofSeconds(3));
        TelegramAdapterLifecycle telegramAdapterLifecycle = new TelegramAdapterLifecycle(
            telegramSourceAdapter, telegramClient, mock(SourceEventSink.class), telegramProperties
        ) {
            @Override
            public void stop() {
                events.add("source");
                super.stop();
            }
        };
        WorkerExecutorConfiguration workerExecutorConfiguration = new WorkerExecutorConfiguration() {
            @Override
            public void shutdown(Duration gracePeriod) {
                events.add("workers");
            }
        };
        StartupCoordinator startupCoordinator = coordinator(
            telegramAdapterLifecycle, new RecordingStagingCleanup()
        );
        ReflectionTestUtils.setField(
            startupCoordinator, "workerExecutorConfiguration", workerExecutorConfiguration
        );
        startupCoordinator.start();

        startupCoordinator.stop();

        assertThat(startupCoordinator.claimsEnabled()).isFalse();
        assertThat(events).containsExactly("source", "workers");
    }

    private StartupCoordinator coordinator(
        TelegramAdapterLifecycle telegramAdapterLifecycle,
        RecordingStagingCleanup stagingCleanup
    ) {
        @SuppressWarnings("unchecked")
        ObjectProvider<TelegramAdapterLifecycle> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(telegramAdapterLifecycle);
        WorkerProperties workerProperties = new WorkerProperties();
        workerProperties.setSchedulingEnabled(true);
        TelegramProperties telegramProperties = new TelegramProperties();
        StartupCoordinator startupCoordinator = new StartupCoordinator();
        ReflectionTestUtils.setField(startupCoordinator, "stagingCleanup", stagingCleanup);
        ReflectionTestUtils.setField(startupCoordinator, "telegramAdapterLifecycleProvider", provider);
        ReflectionTestUtils.setField(startupCoordinator, "telegramProperties", telegramProperties);
        ReflectionTestUtils.setField(startupCoordinator, "workerProperties", workerProperties);
        return startupCoordinator;
    }

    private static class RecordingStagingCleanup extends StagingCleanup {

        private int runs;

        @Override
        public void clean() {
            runs++;
        }

    }

}
