package org.moera.importer.scheduling;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.moera.importer.config.WorkerProperties;
import org.moera.importer.observability.ImportMetrics;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.moera.importer.scheduling.WorkerWorkflow.CHANNEL;
import static org.moera.importer.scheduling.WorkerWorkflow.PUBLICATION_MEDIA;

class WorkerDispatcherTest {

    @Test
    void reservesOnlyAvailableCapacityAndReleasesUnusedSlots() {
        QueueExecutorService executor = new QueueExecutorService();
        WorkerDispatcher dispatcher = dispatcher(executor, 2, 2, 4, 4);

        try (WorkerDispatcher.Reservation reservation = dispatcher.reserve(CHANNEL, 10)) {
            assertThat(reservation.capacity()).isEqualTo(2);
            reservation.dispatch(() -> {
            });
        }

        assertThat(dispatcher.available(CHANNEL)).isEqualTo(1);
        executor.runNext();
        assertThat(dispatcher.available(CHANNEL)).isEqualTo(2);
    }

    @Test
    void workflowsHaveIndependentCapacity() {
        QueueExecutorService executor = new QueueExecutorService();
        WorkerDispatcher dispatcher = dispatcher(executor, 2, 2, 4, 4);

        try (WorkerDispatcher.Reservation channel = dispatcher.reserve(CHANNEL, 10);
             WorkerDispatcher.Reservation media = dispatcher.reserve(PUBLICATION_MEDIA, 10)) {
            assertThat(channel.capacity()).isEqualTo(2);
            assertThat(media.capacity()).isEqualTo(4);
        }

        assertThat(dispatcher.available(CHANNEL)).isEqualTo(2);
        assertThat(dispatcher.available(PUBLICATION_MEDIA)).isEqualTo(4);
    }

    @Test
    void taskFailureReleasesTransferredPermit() {
        QueueExecutorService executor = new QueueExecutorService();
        WorkerDispatcher dispatcher = dispatcher(executor, 1, 1, 1, 1);
        try (WorkerDispatcher.Reservation reservation = dispatcher.reserve(CHANNEL, 1)) {
            reservation.dispatch(() -> {
                throw new IllegalStateException("failed");
            });
        }

        assertThatThrownBy(executor::runNext)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("failed");
        assertThat(dispatcher.available(CHANNEL)).isEqualTo(1);
    }

    @Test
    void rejectedSubmissionReleasesTransferredPermitExactlyOnce() {
        QueueExecutorService executor = new QueueExecutorService();
        executor.reject = true;
        WorkerDispatcher dispatcher = dispatcher(executor, 1, 1, 1, 1);

        try (WorkerDispatcher.Reservation reservation = dispatcher.reserve(CHANNEL, 1)) {
            assertThatThrownBy(() -> reservation.dispatch(() -> {
            })).isInstanceOf(RejectedExecutionException.class);
        }

        assertThat(dispatcher.available(CHANNEL)).isEqualTo(1);
    }

    private WorkerDispatcher dispatcher(
        QueueExecutorService executor,
        int channel,
        int publicationBuilder,
        int publicationMedia,
        int publication
    ) {
        WorkerProperties workerProperties = new WorkerProperties();
        workerProperties.setChannelConcurrency(channel);
        workerProperties.setPublicationBuilderConcurrency(publicationBuilder);
        workerProperties.setPublicationMediaConcurrency(publicationMedia);
        workerProperties.setPublicationConcurrency(publication);
        ImportMetrics importMetrics = new ImportMetrics();
        ReflectionTestUtils.setField(importMetrics, "meterRegistry", new SimpleMeterRegistry());
        WorkerDispatcher workerDispatcher = new WorkerDispatcher();
        ReflectionTestUtils.setField(workerDispatcher, "executorService", executor);
        ReflectionTestUtils.setField(workerDispatcher, "workerProperties", workerProperties);
        ReflectionTestUtils.setField(workerDispatcher, "importMetrics", importMetrics);
        workerDispatcher.initialize();
        return workerDispatcher;
    }

    private static class QueueExecutorService extends AbstractExecutorService {

        private final Queue<Runnable> commands = new ArrayDeque<>();
        private boolean reject;
        private boolean shutdown;

        void runNext() {
            commands.remove().run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.copyOf(commands);
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && commands.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public void execute(Runnable command) {
            if (reject) {
                throw new RejectedExecutionException("rejected");
            }
            commands.add(command);
        }

    }

}
