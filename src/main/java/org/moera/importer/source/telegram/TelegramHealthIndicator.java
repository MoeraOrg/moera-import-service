package org.moera.importer.source.telegram;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "moera.telegram", name = "enabled", havingValue = "true")
public class TelegramHealthIndicator implements HealthIndicator {

    private final TelegramClient client;

    public TelegramHealthIndicator(TelegramClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        return client.isReady()
            ? Health.up().build()
            : Health.down().withDetail("reason", "Telegram session is not authorized").build();
    }

}
