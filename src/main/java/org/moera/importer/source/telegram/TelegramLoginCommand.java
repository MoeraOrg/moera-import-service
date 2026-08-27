package org.moera.importer.source.telegram;

import org.moera.importer.config.TelegramProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "moera.telegram", name = {"enabled", "login"}, havingValue = "true")
public class TelegramLoginCommand implements ApplicationRunner {

    private final TelegramClient client;
    private final TelegramProperties properties;

    public TelegramLoginCommand(TelegramClient client, TelegramProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        client.start(update -> {
        });
        if (!client.awaitReady(properties.getLoginTimeout())) {
            throw new IllegalStateException("Telegram login did not complete before the login timeout");
        }
        client.close();
    }

}
