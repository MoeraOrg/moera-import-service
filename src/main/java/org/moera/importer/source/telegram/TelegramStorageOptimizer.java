package org.moera.importer.source.telegram;

import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "moera.telegram", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "moera.telegram", name = "login", havingValue = "false", matchIfMissing = true)
public class TelegramStorageOptimizer {

    private static final Logger log = LoggerFactory.getLogger(TelegramStorageOptimizer.class);

    @Inject
    private TelegramClient telegramClient;

    @Scheduled(fixedDelayString = "PT6H")
    public void run() {
        if (!telegramClient.isReady()) {
            return;
        }
        try {
            telegramClient.optimizeStorage();
        } catch (RuntimeException e) {
            log.warn("Telegram storage optimization failed", e);
        }
    }

}
