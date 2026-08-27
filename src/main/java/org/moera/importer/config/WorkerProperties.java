package org.moera.importer.config;

import java.nio.file.Path;
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
    private Duration publicationMediaLease = Duration.ofMinutes(5);
    private Duration publicationMediaScheduleInterval = Duration.ofSeconds(1);
    private Duration publicationLease = Duration.ofMinutes(5);
    private Duration publicationScheduleInterval = Duration.ofSeconds(1);
    private Duration mediaCompressionPollInterval = Duration.ofSeconds(10);
    private Path stagingDirectory = Path.of("var/staging");
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

    public Path getStagingDirectory() {
        return stagingDirectory;
    }

    public void setStagingDirectory(Path stagingDirectory) {
        this.stagingDirectory = stagingDirectory;
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
