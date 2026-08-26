package org.moera.importer;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.moera.importer.application.RetryPolicy;
import org.moera.importer.config.WorkerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class MoeraImportApplication {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    public ExecutorService executorService() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public RetryPolicy retryPolicy(WorkerProperties workerProperties) {
        return new RetryPolicy(
            workerProperties.getRetryBaseDelay(),
            workerProperties.getRetryMaxDelay(),
            workerProperties.getRetryJitterFraction()
        );
    }

    public static void main(String[] args) {
        SpringApplication.run(MoeraImportApplication.class, args);
    }

}
