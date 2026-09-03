package org.moera.importer.config;

import java.nio.file.Path;
import java.time.Duration;
import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "moera.telegram")
public class TelegramProperties {

    private boolean enabled;
    private boolean login;
    private int apiId;
    private String apiHash = "";
    private Path databaseDirectory = Path.of("var/telegram/database");
    private Path downloadCacheDirectory = Path.of("var/telegram/cache");
    private Duration groupingThreshold = Duration.ofMinutes(5);
    private Duration settlingDelay = Duration.ofSeconds(30);
    private Duration pollingInterval = Duration.ofMinutes(30);
    private Duration inactivePollingInterval = Duration.ofMinutes(10);
    private int lookBehindCount = 20;
    private int historyPageSize = 100;
    private int initialHistoryCount = 20;
    private Duration readinessTimeout = Duration.ofSeconds(30);
    private Duration loginTimeout = Duration.ofMinutes(10);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isLogin() {
        return login;
    }

    public void setLogin(boolean login) {
        this.login = login;
    }

    public int getApiId() {
        return apiId;
    }

    public void setApiId(int apiId) {
        this.apiId = apiId;
    }

    public String getApiHash() {
        return apiHash;
    }

    public void setApiHash(String apiHash) {
        this.apiHash = apiHash;
    }

    public Path getDatabaseDirectory() {
        return databaseDirectory;
    }

    public void setDatabaseDirectory(Path databaseDirectory) {
        this.databaseDirectory = databaseDirectory;
    }

    public Path getDownloadCacheDirectory() {
        return downloadCacheDirectory;
    }

    public void setDownloadCacheDirectory(Path downloadCacheDirectory) {
        this.downloadCacheDirectory = downloadCacheDirectory;
    }

    public Duration getGroupingThreshold() {
        return groupingThreshold;
    }

    public void setGroupingThreshold(Duration groupingThreshold) {
        this.groupingThreshold = groupingThreshold;
    }

    public Duration getSettlingDelay() {
        return settlingDelay;
    }

    public void setSettlingDelay(Duration settlingDelay) {
        this.settlingDelay = settlingDelay;
    }

    public Duration getPollingInterval() {
        return pollingInterval;
    }

    public void setPollingInterval(Duration pollingInterval) {
        this.pollingInterval = pollingInterval;
    }

    public Duration getInactivePollingInterval() {
        return inactivePollingInterval;
    }

    public void setInactivePollingInterval(Duration inactivePollingInterval) {
        this.inactivePollingInterval = inactivePollingInterval;
    }

    public int getLookBehindCount() {
        return lookBehindCount;
    }

    public void setLookBehindCount(int lookBehindCount) {
        this.lookBehindCount = lookBehindCount;
    }

    public int getHistoryPageSize() {
        return historyPageSize;
    }

    public void setHistoryPageSize(int historyPageSize) {
        this.historyPageSize = historyPageSize;
    }

    public int getInitialHistoryCount() {
        return initialHistoryCount;
    }

    public void setInitialHistoryCount(int initialHistoryCount) {
        this.initialHistoryCount = initialHistoryCount;
    }

    public Duration getReadinessTimeout() {
        return readinessTimeout;
    }

    public void setReadinessTimeout(Duration readinessTimeout) {
        this.readinessTimeout = readinessTimeout;
    }

    public Duration getLoginTimeout() {
        return loginTimeout;
    }

    public void setLoginTimeout(Duration loginTimeout) {
        this.loginTimeout = loginTimeout;
    }

    @PostConstruct
    void validate() {
        if (initialHistoryCount <= 0) {
            throw new IllegalStateException("Telegram initial history count must be positive");
        }
    }

}
