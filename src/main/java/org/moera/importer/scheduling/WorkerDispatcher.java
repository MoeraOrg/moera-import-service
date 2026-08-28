package org.moera.importer.scheduling;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

import org.moera.importer.config.WorkerProperties;
import org.moera.importer.observability.ImportMetrics;
import org.springframework.stereotype.Component;

@Component
public class WorkerDispatcher {

    private final Map<WorkerWorkflow, Semaphore> permits = new EnumMap<>(WorkerWorkflow.class);

    @Inject
    private ExecutorService executorService;

    @Inject
    private WorkerProperties workerProperties;

    @Inject
    private ImportMetrics importMetrics;

    @PostConstruct
    void initialize() {
        permits.clear();
        register(WorkerWorkflow.CHANNEL, workerProperties.getChannelConcurrency());
        register(WorkerWorkflow.PUBLICATION_BUILDER, workerProperties.getPublicationBuilderConcurrency());
        register(WorkerWorkflow.PUBLICATION_MEDIA, workerProperties.getPublicationMediaConcurrency());
        register(WorkerWorkflow.PUBLICATION, workerProperties.getPublicationConcurrency());
    }

    public Reservation reserve(WorkerWorkflow workflow, int maximum) {
        Objects.requireNonNull(workflow, "workflow");
        if (maximum < 0) {
            throw new IllegalArgumentException("Maximum reservation capacity cannot be negative");
        }
        Semaphore semaphore = permits.get(workflow);
        int capacity = 0;
        while (capacity < maximum && semaphore.tryAcquire()) {
            capacity++;
        }
        return new Reservation(semaphore, capacity);
    }

    public int available(WorkerWorkflow workflow) {
        return permits.get(Objects.requireNonNull(workflow, "workflow")).availablePermits();
    }

    private void register(WorkerWorkflow workflow, int limit) {
        permits.put(workflow, new Semaphore(limit, true));
        importMetrics.workerActiveTasks(workflow, () -> limit - available(workflow));
    }

    public final class Reservation implements AutoCloseable {

        private final Semaphore semaphore;
        private final int capacity;
        private int dispatched;
        private boolean closed;

        private Reservation(Semaphore semaphore, int capacity) {
            this.semaphore = semaphore;
            this.capacity = capacity;
        }

        public int capacity() {
            return capacity;
        }

        public synchronized void dispatch(Runnable task) {
            Objects.requireNonNull(task, "task");
            if (closed) {
                throw new IllegalStateException("Reservation is closed");
            }
            if (dispatched == capacity) {
                throw new IllegalStateException("Reservation capacity is exhausted");
            }
            dispatched++;
            AtomicBoolean released = new AtomicBoolean();
            Runnable release = () -> {
                if (released.compareAndSet(false, true)) {
                    semaphore.release();
                }
            };
            try {
                executorService.execute(() -> {
                    try {
                        task.run();
                    } finally {
                        release.run();
                    }
                });
            } catch (RuntimeException e) {
                release.run();
                throw e;
            }
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            semaphore.release(capacity - dispatched);
        }

    }

}
