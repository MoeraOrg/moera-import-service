package org.moera.importer.config;

import java.time.Duration;
import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "moera.workers")
public class WorkerProperties {

    private boolean schedulingEnabled = true;
    private int batchSize = 32;
    private int channelConcurrency = 2;
    private int publicationBuilderConcurrency = 2;
    private int publicationMediaConcurrency = 4;
    private int publicationConcurrency = 4;
    private Duration channelLease = Duration.ofMinutes(5);
    private Duration channelScheduleInterval = Duration.ofSeconds(1);
    private Duration publicationBuilderScheduleInterval = Duration.ofSeconds(1);
    private Duration publicationMediaLease = Duration.ofMinutes(5);
    private Duration publicationMediaScheduleInterval = Duration.ofSeconds(1);
    private Duration publicationLease = Duration.ofMinutes(5);
    private Duration publicationScheduleInterval = Duration.ofSeconds(1);
    private Duration mediaCompressionPollInterval = Duration.ofSeconds(10);
    private Duration shutdownGracePeriod = Duration.ofSeconds(30);

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

    public int getChannelConcurrency() {
        return channelConcurrency;
    }

    public void setChannelConcurrency(int channelConcurrency) {
        this.channelConcurrency = channelConcurrency;
    }

    public int getPublicationBuilderConcurrency() {
        return publicationBuilderConcurrency;
    }

    public void setPublicationBuilderConcurrency(int publicationBuilderConcurrency) {
        this.publicationBuilderConcurrency = publicationBuilderConcurrency;
    }

    public int getPublicationMediaConcurrency() {
        return publicationMediaConcurrency;
    }

    public void setPublicationMediaConcurrency(int publicationMediaConcurrency) {
        this.publicationMediaConcurrency = publicationMediaConcurrency;
    }

    public int getPublicationConcurrency() {
        return publicationConcurrency;
    }

    public void setPublicationConcurrency(int publicationConcurrency) {
        this.publicationConcurrency = publicationConcurrency;
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

    public Duration getPublicationMediaLease() {
        return publicationMediaLease;
    }

    public void setPublicationMediaLease(Duration publicationMediaLease) {
        this.publicationMediaLease = publicationMediaLease;
    }

    public Duration getPublicationMediaScheduleInterval() {
        return publicationMediaScheduleInterval;
    }

    public void setPublicationMediaScheduleInterval(Duration publicationMediaScheduleInterval) {
        this.publicationMediaScheduleInterval = publicationMediaScheduleInterval;
    }

    public Duration getPublicationLease() {
        return publicationLease;
    }

    public void setPublicationLease(Duration publicationLease) {
        this.publicationLease = publicationLease;
    }

    public Duration getPublicationScheduleInterval() {
        return publicationScheduleInterval;
    }

    public void setPublicationScheduleInterval(Duration publicationScheduleInterval) {
        this.publicationScheduleInterval = publicationScheduleInterval;
    }

    public Duration getMediaCompressionPollInterval() {
        return mediaCompressionPollInterval;
    }

    public void setMediaCompressionPollInterval(Duration mediaCompressionPollInterval) {
        this.mediaCompressionPollInterval = mediaCompressionPollInterval;
    }

    public Duration getShutdownGracePeriod() {
        return shutdownGracePeriod;
    }

    public void setShutdownGracePeriod(Duration shutdownGracePeriod) {
        this.shutdownGracePeriod = shutdownGracePeriod;
    }

    @PostConstruct
    void validate() {
        if (batchSize <= 0 || channelConcurrency <= 0 || publicationBuilderConcurrency <= 0
            || publicationMediaConcurrency <= 0 || publicationConcurrency <= 0
            || !positive(channelLease) || !positive(publicationMediaLease) || !positive(publicationLease)) {
            throw new IllegalStateException("Worker capacities and leases must be positive");
        }
    }

    private boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }

}
