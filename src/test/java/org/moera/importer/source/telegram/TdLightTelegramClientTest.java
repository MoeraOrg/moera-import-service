package org.moera.importer.source.telegram;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import it.tdlight.ClientFactory;
import it.tdlight.UpdatesHandler;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.moera.importer.config.TelegramProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TdLightTelegramClientTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void startsLowLevelClientWithoutLoadingChatLists() {
        ClientFactory clientFactory = mock(ClientFactory.class);
        it.tdlight.TelegramClient nativeClient = mock(it.tdlight.TelegramClient.class);
        when(clientFactory.createClient()).thenReturn(nativeClient);
        TelegramProperties properties = properties();
        TdLightTelegramClient client = new TdLightTelegramClient(
            properties,
            false,
            () -> clientFactory,
            () -> mock(TdLightLoginPrompt.class)
        );
        ArgumentCaptor<UpdatesHandler> updatesHandler = ArgumentCaptor.forClass(UpdatesHandler.class);

        client.start(ignored -> {
        });
        verify(nativeClient).initialize(updatesHandler.capture(), any(), any());
        updatesHandler.getValue().onUpdates(List.of(
            new TdApi.UpdateAuthorizationState(new TdApi.AuthorizationStateReady())
        ));

        assertThat(client.isReady()).isTrue();
        verifyNoMoreInteractions(nativeClient);
    }

    @Test
    void releasesFactoryWhenCloseRequestFails() {
        ClientFactory clientFactory = mock(ClientFactory.class);
        it.tdlight.TelegramClient nativeClient = mock(it.tdlight.TelegramClient.class);
        when(clientFactory.createClient()).thenReturn(nativeClient);
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("close failed")))
            .when(nativeClient).sendAsync(isA(TdApi.Close.class));
        TdLightTelegramClient client = new TdLightTelegramClient(
            properties(),
            false,
            () -> clientFactory,
            () -> mock(TdLightLoginPrompt.class)
        );
        client.start(ignored -> {
        });

        assertThatThrownBy(client::close).hasRootCauseMessage("close failed");

        verify(clientFactory).close();
    }

    @Test
    void readsOwnMembershipFromSupergroupWhenMemberListIsInaccessible() {
        ClientFactory clientFactory = mock(ClientFactory.class);
        it.tdlight.TelegramClient nativeClient = mock(it.tdlight.TelegramClient.class);
        when(clientFactory.createClient()).thenReturn(nativeClient);
        TdApi.Chat chat = new TdApi.Chat();
        chat.type = new TdApi.ChatTypeSupergroup(77, true);
        TdApi.Supergroup supergroup = new TdApi.Supergroup();
        supergroup.status = new TdApi.ChatMemberStatusLeft();
        TdApi.User self = new TdApi.User();
        self.id = 11;
        doReturn(CompletableFuture.completedFuture(chat))
            .when(nativeClient).sendAsync(isA(TdApi.GetChat.class));
        doReturn(CompletableFuture.completedFuture(supergroup))
            .when(nativeClient).sendAsync(isA(TdApi.GetSupergroup.class));
        doReturn(CompletableFuture.completedFuture(self))
            .when(nativeClient).sendAsync(isA(TdApi.GetMe.class));
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("Member list is inaccessible")))
            .when(nativeClient).sendAsync(isA(TdApi.GetChatMember.class));
        TdLightTelegramClient client = new TdLightTelegramClient(
            properties(),
            false,
            () -> clientFactory,
            () -> mock(TdLightLoginPrompt.class)
        );
        client.start(ignored -> {
        });

        assertThat(client.membership(-100_000_000_0077L)).isEqualTo(TelegramClient.Membership.INACTIVE);
    }

    private TelegramProperties properties() {
        TelegramProperties properties = new TelegramProperties();
        properties.setApiId(1);
        properties.setApiHash("hash");
        properties.setDatabaseDirectory(temporaryDirectory.resolve("database"));
        properties.setDownloadCacheDirectory(temporaryDirectory.resolve("cache"));
        return properties;
    }

}
