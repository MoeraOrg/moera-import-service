package org.moera.importer.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "moera.retry")
public class RetryProperties {

    private Duration baseDelay = Duration.ofSeconds(1);
    private Duration maxDelay = Duration.ofHours(1);
    private Duration fatalAfter = Duration.ofDays(1);
    private double jitterFraction = 0.2;

    public Duration getBaseDelay() {
        return baseDelay;
    }

    public void setBaseDelay(Duration baseDelay) {
        this.baseDelay = baseDelay;
    }

    public Duration getMaxDelay() {
        return maxDelay;
    }

    public void setMaxDelay(Duration maxDelay) {
        this.maxDelay = maxDelay;
    }

    public Duration getFatalAfter() {
        return fatalAfter;
    }

    public void setFatalAfter(Duration fatalAfter) {
        this.fatalAfter = fatalAfter;
    }

    public double getJitterFraction() {
        return jitterFraction;
    }

    public void setJitterFraction(double jitterFraction) {
        this.jitterFraction = jitterFraction;
    }

}
