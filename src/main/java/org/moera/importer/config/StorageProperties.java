package org.moera.importer.config;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "moera.storage")
public class StorageProperties {

    private Path stagingDirectory = Path.of("var/staging");
    private Duration stagingGracePeriod = Duration.ofHours(1);
    private Duration cleanupInterval = Duration.ofHours(1);
    private int cleanupBatchSize = 500;

    public Path getStagingDirectory() {
        return stagingDirectory;
    }

    public void setStagingDirectory(Path stagingDirectory) {
        this.stagingDirectory = stagingDirectory;
    }

    public Duration getStagingGracePeriod() {
        return stagingGracePeriod;
    }

    public void setStagingGracePeriod(Duration stagingGracePeriod) {
        this.stagingGracePeriod = stagingGracePeriod;
    }

    public Duration getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(Duration cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }

    public int getCleanupBatchSize() {
        return cleanupBatchSize;
    }

    public void setCleanupBatchSize(int cleanupBatchSize) {
        this.cleanupBatchSize = cleanupBatchSize;
    }

}
