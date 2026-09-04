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
import org.moera.importer.source.ChannelMetadata;
import org.moera.importer.source.SourceTemporaryException;

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
    void reportsFailedTelegramOperationAndCause() {
        ClientFactory clientFactory = mock(ClientFactory.class);
        it.tdlight.TelegramClient nativeClient = mock(it.tdlight.TelegramClient.class);
        when(clientFactory.createClient()).thenReturn(nativeClient);
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("remote file not found")))
            .when(nativeClient).sendAsync(isA(TdApi.GetRemoteFile.class));
        TdLightTelegramClient client = new TdLightTelegramClient(
            properties(),
            false,
            () -> clientFactory,
            () -> mock(TdLightLoginPrompt.class)
        );
        client.start(ignored -> {
        });

        assertThatThrownBy(() -> client.reacquire("remote-id", "photo"))
            .isInstanceOf(SourceTemporaryException.class)
            .hasMessage("Telegram operation GetRemoteFile failed: remote file not found");
    }

    @Test
    void reacquiresThumbnailWithTelegramThumbnailFileType() {
        ClientFactory clientFactory = mock(ClientFactory.class);
        it.tdlight.TelegramClient nativeClient = mock(it.tdlight.TelegramClient.class);
        when(clientFactory.createClient()).thenReturn(nativeClient);
        TdApi.File file = new TdApi.File();
        file.id = 123;
        doReturn(CompletableFuture.completedFuture(file))
            .when(nativeClient).sendAsync(isA(TdApi.GetRemoteFile.class));
        TdLightTelegramClient client = new TdLightTelegramClient(
            properties(),
            false,
            () -> clientFactory,
            () -> mock(TdLightLoginPrompt.class)
        );
        client.start(ignored -> {
        });
        ArgumentCaptor<TdApi.GetRemoteFile> request = ArgumentCaptor.forClass(TdApi.GetRemoteFile.class);

        assertThat(client.reacquire("remote-id", "thumbnail").fileId()).isEqualTo(123);
        verify(nativeClient).sendAsync(request.capture());
        assertThat(request.getValue().fileType).isInstanceOf(TdApi.FileTypeThumbnail.class);
    }

    @Test
    void reacquiresChannelEmblemWithTelegramProfilePhotoFileType() {
        ClientFactory clientFactory = mock(ClientFactory.class);
        it.tdlight.TelegramClient nativeClient = mock(it.tdlight.TelegramClient.class);
        when(clientFactory.createClient()).thenReturn(nativeClient);
        TdApi.File file = new TdApi.File();
        file.id = 123;
        doReturn(CompletableFuture.completedFuture(file))
            .when(nativeClient).sendAsync(isA(TdApi.GetRemoteFile.class));
        TdLightTelegramClient client = new TdLightTelegramClient(
            properties(),
            false,
            () -> clientFactory,
            () -> mock(TdLightLoginPrompt.class)
        );
        client.start(ignored -> {
        });
        ArgumentCaptor<TdApi.GetRemoteFile> request = ArgumentCaptor.forClass(TdApi.GetRemoteFile.class);

        assertThat(client.reacquire("remote-id", "profilePhoto").fileId()).isEqualTo(123);
        verify(nativeClient).sendAsync(request.capture());
        assertThat(request.getValue().fileType).isInstanceOf(TdApi.FileTypeProfilePhoto.class);
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

    @Test
    void resolvesForwardChannelNameAndPrimaryUsernameByChatId() {
        ClientFactory clientFactory = mock(ClientFactory.class);
        it.tdlight.TelegramClient nativeClient = mock(it.tdlight.TelegramClient.class);
        when(clientFactory.createClient()).thenReturn(nativeClient);
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = -1_001_809_632_446L;
        chat.title = "Example channel";
        chat.type = new TdApi.ChatTypeSupergroup(1_809_632_446L, true);
        TdApi.Supergroup supergroup = new TdApi.Supergroup();
        supergroup.usernames = new TdApi.Usernames(
            new String[] {"example_channel", "secondary_name"}, new String[0], "", new String[0]
        );
        doReturn(CompletableFuture.completedFuture(chat))
            .when(nativeClient).sendAsync(isA(TdApi.GetChat.class));
        doReturn(CompletableFuture.completedFuture(supergroup))
            .when(nativeClient).sendAsync(isA(TdApi.GetSupergroup.class));
        TdLightTelegramClient client = new TdLightTelegramClient(
            properties(),
            false,
            () -> clientFactory,
            () -> mock(TdLightLoginPrompt.class)
        );
        client.start(ignored -> {
        });
        ArgumentCaptor<TdApi.GetChat> chatRequest = ArgumentCaptor.forClass(TdApi.GetChat.class);
        ArgumentCaptor<TdApi.GetSupergroup> supergroupRequest = ArgumentCaptor.forClass(TdApi.GetSupergroup.class);

        assertThat(client.chat(-1_001_809_632_446L))
            .isEqualTo(new TelegramForwardMetadataResolver.Source("Example channel", "example_channel"));
        verify(nativeClient).sendAsync(chatRequest.capture());
        verify(nativeClient).sendAsync(supergroupRequest.capture());
        assertThat(chatRequest.getValue().chatId).isEqualTo(-1_001_809_632_446L);
        assertThat(supergroupRequest.getValue().supergroupId).isEqualTo(1_809_632_446L);
    }

    @Test
    void requestsPrivateMessageLinkFromTelegram() {
        ClientFactory clientFactory = mock(ClientFactory.class);
        it.tdlight.TelegramClient nativeClient = mock(it.tdlight.TelegramClient.class);
        when(clientFactory.createClient()).thenReturn(nativeClient);
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = -1_000_000_001_001L;
        chat.title = "Private channel";
        doReturn(CompletableFuture.completedFuture(chat))
            .when(nativeClient).sendAsync(isA(TdApi.GetChat.class));
        doReturn(CompletableFuture.completedFuture(new TdApi.MessageLink(
            "https://t.me/c/1001/6660", false
        ))).when(nativeClient).sendAsync(isA(TdApi.GetMessageLink.class));
        TdLightTelegramClient client = new TdLightTelegramClient(
            properties(),
            false,
            () -> clientFactory,
            () -> mock(TdLightLoginPrompt.class)
        );
        client.start(ignored -> {
        });
        long messageId = 6660L << 20;
        ArgumentCaptor<TdApi.GetMessageLink> request = ArgumentCaptor.forClass(TdApi.GetMessageLink.class);

        assertThat(client.messageLink(chat.id, messageId)).isEqualTo("https://t.me/c/1001/6660");
        verify(nativeClient).sendAsync(request.capture());
        assertThat(request.getValue().chatId).isEqualTo(chat.id);
        assertThat(request.getValue().messageId).isEqualTo(messageId);
    }

    @Test
    void readsCompleteChannelMetadataAndLargePhotoIdentity() {
        ClientFactory clientFactory = mock(ClientFactory.class);
        it.tdlight.TelegramClient nativeClient = mock(it.tdlight.TelegramClient.class);
        when(clientFactory.createClient()).thenReturn(nativeClient);
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = -1_000_000_000_077L;
        chat.title = "News";
        chat.type = new TdApi.ChatTypeSupergroup(77, true);
        TdApi.File big = new TdApi.File();
        big.remote = new TdApi.RemoteFile();
        big.remote.id = "remote-photo";
        big.remote.uniqueId = "unique-photo";
        chat.photo = new TdApi.ChatPhotoInfo();
        chat.photo.big = big;
        TdApi.Supergroup supergroup = new TdApi.Supergroup();
        supergroup.usernames = new TdApi.Usernames(new String[] {"news"}, new String[0], "", new String[0]);
        TdApi.SupergroupFullInfo fullInfo = new TdApi.SupergroupFullInfo();
        fullInfo.description = "😀 Visit https://example.org, mail news@example.org, ask @admin and #ignored";
        doReturn(CompletableFuture.completedFuture(chat))
            .when(nativeClient).sendAsync(isA(TdApi.GetChat.class));
        doReturn(CompletableFuture.completedFuture(supergroup))
            .when(nativeClient).sendAsync(isA(TdApi.GetSupergroup.class));
        doReturn(CompletableFuture.completedFuture(fullInfo))
            .when(nativeClient).sendAsync(isA(TdApi.GetSupergroupFullInfo.class));
        doReturn(CompletableFuture.completedFuture(new TdApi.TextEntities(new TdApi.TextEntity[] {
            textEntity(fullInfo.description, "https://example.org", new TdApi.TextEntityTypeUrl()),
            textEntity(fullInfo.description, "news@example.org", new TdApi.TextEntityTypeEmailAddress()),
            textEntity(fullInfo.description, "@admin", new TdApi.TextEntityTypeMention()),
            textEntity(fullInfo.description, "#ignored", new TdApi.TextEntityTypeHashtag())
        }))).when(nativeClient).sendAsync(isA(TdApi.GetTextEntities.class));
        TdLightTelegramClient client = new TdLightTelegramClient(
            properties(), false, () -> clientFactory, () -> mock(TdLightLoginPrompt.class)
        );
        client.start(ignored -> {
        });

        var metadata = client.channelMetadata(chat.id);

        assertThat(metadata.title()).isEqualTo("News");
        assertThat(metadata.descriptionHtml()).isEqualTo(
            "<p>😀 Visit <a href=\"https://example.org\">https://example.org</a>, mail "
                + "<a href=\"mailto:news@example.org\">news@example.org</a>, ask "
                + "<a href=\"https://t.me/admin\">@admin</a> and #ignored</p>"
        );
        assertThat(metadata.publicUsername()).isEqualTo("news");
        assertThat(metadata.emblem().remoteId()).isEqualTo("remote-photo");
        assertThat(metadata.emblem().uniqueId()).isEqualTo("unique-photo");
    }

    private static TdApi.TextEntity textEntity(String text, String value, TdApi.TextEntityType type) {
        return new TdApi.TextEntity(text.indexOf(value), value.length(), type);
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
