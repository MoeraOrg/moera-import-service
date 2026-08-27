package org.moera.importer.source.telegram;

import org.moera.importer.config.TelegramProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class TelegramRuntime {

    private final TelegramProperties properties;
    private final ObjectProvider<TelegramClient> clientProvider;

    public TelegramRuntime(TelegramProperties properties, ObjectProvider<TelegramClient> clientProvider) {
        this.properties = properties;
        this.clientProvider = clientProvider;
    }

    public boolean channelWorkReady() {
        if (!properties.isEnabled() || properties.isLogin()) {
            return false;
        }
        TelegramClient client = clientProvider.getIfAvailable();
        return client != null && client.isReady();
    }

}
