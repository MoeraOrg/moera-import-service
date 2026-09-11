package org.moera.importer;

import java.time.Clock;
import org.moera.importer.application.RetryPolicy;
import org.moera.importer.config.RetryProperties;
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

    @Bean
    public RetryPolicy retryPolicy(RetryProperties retryProperties) {
        return new RetryPolicy(
            retryProperties.getBaseDelay(),
            retryProperties.getMaxDelay(),
            retryProperties.getFatalAfter(),
            retryProperties.getJitterFraction()
        );
    }

    public static void main(String[] args) {
        SpringApplication.run(MoeraImportApplication.class, args);
    }

}
