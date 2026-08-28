package org.moera.importer.config;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class WorkerExecutorConfiguration {

    private static final Duration FORCED_SHUTDOWN_WAIT = Duration.ofSeconds(1);

    private ExecutorService executorService;

    @Bean
    public ExecutorService executorService() {
        executorService = Executors.newVirtualThreadPerTaskExecutor();
        return executorService;
    }

    public void shutdown(Duration gracePeriod) {
        ExecutorService executor = executorService;
        if (executor == null || executor.isShutdown()) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(gracePeriod.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(FORCED_SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void destroy() {
        shutdown(Duration.ZERO);
    }

}
