package org.moera.importer.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "moera.workers")
public class WorkerProperties {

    private boolean schedulingEnabled = true;
    private int batchSize = 32;
    private Duration channelLease = Duration.ofMinutes(5);
    private Duration channelScheduleInterval = Duration.ofSeconds(1);
    private Duration publicationBuilderScheduleInterval = Duration.ofSeconds(1);
    private Duration retryBaseDelay = Duration.ofSeconds(1);
    private Duration retryMaxDelay = Duration.ofHours(1);
    private double retryJitterFraction = 0.2;

    public boolean isSchedulingEnabled() {
        return schedulingEnabled;
    }

    public void setSchedulingEnabled(boolean schedulingEnabled) {
        this.schedulingEnabled = schedulingEnabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getChannelLease() {
        return channelLease;
    }

    public void setChannelLease(Duration channelLease) {
        this.channelLease = channelLease;
    }

    public Duration getChannelScheduleInterval() {
        return channelScheduleInterval;
    }

    public void setChannelScheduleInterval(Duration channelScheduleInterval) {
        this.channelScheduleInterval = channelScheduleInterval;
    }

    public Duration getPublicationBuilderScheduleInterval() {
        return publicationBuilderScheduleInterval;
    }

    public void setPublicationBuilderScheduleInterval(Duration publicationBuilderScheduleInterval) {
        this.publicationBuilderScheduleInterval = publicationBuilderScheduleInterval;
    }

    public Duration getRetryBaseDelay() {
        return retryBaseDelay;
    }

    public void setRetryBaseDelay(Duration retryBaseDelay) {
        this.retryBaseDelay = retryBaseDelay;
    }

    public Duration getRetryMaxDelay() {
        return retryMaxDelay;
    }

    public void setRetryMaxDelay(Duration retryMaxDelay) {
        this.retryMaxDelay = retryMaxDelay;
    }

    public double getRetryJitterFraction() {
        return retryJitterFraction;
    }

    public void setRetryJitterFraction(double retryJitterFraction) {
        this.retryJitterFraction = retryJitterFraction;
    }

}
