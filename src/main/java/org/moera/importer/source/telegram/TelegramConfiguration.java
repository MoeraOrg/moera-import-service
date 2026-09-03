package org.moera.importer.source.telegram;

import org.moera.importer.config.TelegramProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "moera.telegram", name = "enabled", havingValue = "true")
public class TelegramConfiguration {

    @Bean
    public TdLightTelegramClient telegramClient(TelegramProperties properties) {
        return new TdLightTelegramClient(properties, properties.isLogin());
    }

    @Bean
    public TelegramSourceAdapter telegramSourceAdapter(
        TelegramClient client,
        TelegramMessageMapper mapper,
        TelegramProperties properties
    ) {
        return new TelegramSourceAdapter(client, mapper, properties);
    }

}
