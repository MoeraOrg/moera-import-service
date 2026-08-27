package org.moera.importer.config;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerExecutorConfigurationTest {

    @Test
    void interruptsWorkerAfterBoundedShutdownGraceExpires() throws Exception {
        WorkerExecutorConfiguration configuration = new WorkerExecutorConfiguration();
        ExecutorService executorService = configuration.executorService();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        executorService.submit(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        });
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        configuration.shutdown(Duration.ofMillis(10));

        assertThat(executorService.isShutdown()).isTrue();
        assertThat(interrupted).isTrue();
    }

}
