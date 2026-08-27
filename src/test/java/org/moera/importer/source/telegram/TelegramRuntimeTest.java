package org.moera.importer.source.telegram;

import org.junit.jupiter.api.Test;
import org.moera.importer.config.TelegramProperties;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelegramRuntimeTest {

    @Test
    void channelWorkRequiresEnabledNonLoginReadyClient() {
        TelegramProperties properties = new TelegramProperties();
        @SuppressWarnings("unchecked")
        ObjectProvider<TelegramClient> provider = mock(ObjectProvider.class);
        TelegramClient client = mock(TelegramClient.class);
        when(provider.getIfAvailable()).thenReturn(client);
        when(client.isReady()).thenReturn(true);

        TelegramRuntime runtime = new TelegramRuntime(properties, provider);
        assertThat(runtime.channelWorkReady()).isFalse();

        properties.setEnabled(true);
        properties.setLogin(true);
        assertThat(runtime.channelWorkReady()).isFalse();

        properties.setLogin(false);
        assertThat(runtime.channelWorkReady()).isTrue();

        when(client.isReady()).thenReturn(false);
        assertThat(runtime.channelWorkReady()).isFalse();
    }

}
