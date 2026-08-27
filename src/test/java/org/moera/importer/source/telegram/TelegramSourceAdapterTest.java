package org.moera.importer.source.telegram;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.moera.importer.config.TelegramProperties;
import org.moera.importer.model.AccessState;
import org.moera.importer.model.SourceType;
import org.moera.importer.model.SubscriptionState;
import org.moera.importer.observability.ImportMetrics;
import org.moera.importer.source.ChannelResolution;
import org.moera.importer.source.ChannelSnapshot;
import org.moera.importer.source.SourcePermanentException;
import org.moera.importer.source.SourceTemporaryException;
import org.moera.importer.source.SourceEvent;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramSourceAdapterTest {

    private final FakeTelegramClient client = new FakeTelegramClient();
    private TelegramSourceAdapter adapter;
    private SimpleMeterRegistry meterRegistry;

    @TempDir
    private Path temporaryDirectory;

    @BeforeEach
    void setUp() {
        TelegramProperties properties = new TelegramProperties();
        properties.setGroupingThreshold(Duration.ofMinutes(3));
        properties.setSettlingDelay(Duration.ofSeconds(20));
        properties.setPollingInterval(Duration.ofMinutes(2));
        properties.setInactivePollingInterval(Duration.ofMinutes(15));
        adapter = new TelegramSourceAdapter(client, new TelegramMessageMapper(), properties);
        meterRegistry = new SimpleMeterRegistry();
        ImportMetrics importMetrics = new ImportMetrics();
        ReflectionTestUtils.setField(importMetrics, "meterRegistry", meterRegistry);
        ReflectionTestUtils.setField(adapter, "importMetrics", importMetrics);
    }

    @AfterEach
    void tearDown() {
        adapter.stop();
    }

    @Test
    void resolvesPublicChannelToStableDecimalChatId() {
        client.publicChannel = new TelegramClient.Channel(-1001234567890L, "News");
        client.membership = TelegramClient.Membership.INACTIVE;

        ChannelResolution result = adapter.resolveChannel(URI.create("https://t.me/news"));

        assertThat(result.sourceId()).isEqualTo("-1001234567890");
        assertThat(result.sourceUri()).isEqualTo(URI.create("https://t.me/news"));
        assertThat(result.sourceData().path("username").asText()).isEqualTo("news");
        assertThat(result.accessState()).isEqualTo(AccessState.GRANTED);
        assertThat(result.subscriptionState()).isEqualTo(SubscriptionState.INACTIVE);
    }

    @Test
    void resolvesAlreadyJoinedPublicChannelWithActiveSubscription() {
        client.publicChannel = new TelegramClient.Channel(-1001234567890L, "News");
        client.membership = TelegramClient.Membership.ACTIVE;

        ChannelResolution result = adapter.resolveChannel(URI.create("https://t.me/news"));

        assertThat(result.subscriptionState()).isEqualTo(SubscriptionState.ACTIVE);
    }

    @Test
    void resolvesBareUsernameAndCanonicalizesItsUri() {
        client.publicChannel = new TelegramClient.Channel(-1001234567890L, "News");
        client.membership = TelegramClient.Membership.INACTIVE;

        ChannelResolution result = adapter.resolveChannel(URI.create("news"));

        assertThat(client.resolvedUsername).isEqualTo("news");
        assertThat(result.sourceUri()).isEqualTo(URI.create("https://t.me/news"));
    }

    @Test
    void resolvesTelegramDeepLink() {
        client.publicChannel = new TelegramClient.Channel(-1001234567890L, "News");
        client.membership = TelegramClient.Membership.INACTIVE;

        ChannelResolution result = adapter.resolveChannel(URI.create("tg://resolve?domain=news"));

        assertThat(client.resolvedUsername).isEqualTo("news");
        assertThat(result.sourceUri()).isEqualTo(URI.create("https://t.me/news"));
    }

    @Test
    void canonicalizesTelegramInviteDeepLinkBeforeInspection() {
        client.invite = new TelegramClient.Invite(0, "Private channel", true);

        ChannelResolution result = adapter.resolveChannel(URI.create("tg://join?invite=secret"));

        assertThat(client.inspectedInviteLink).isEqualTo("https://t.me/+secret");
        assertThat(result.sourceUri()).isEqualTo(URI.create("https://t.me/+secret"));
    }

    @Test
    void rejectsNonTelegramHostsWithoutCallingTelegram() {
        assertThatThrownBy(() -> adapter.resolveChannel(URI.create("https://example.com/news")))
            .isInstanceOf(SourcePermanentException.class)
            .hasMessageContaining("Invalid Telegram channel URI");
        assertThat(client.resolvedUsername).isNull();
    }

    @Test
    void keepsPrivateInviteUnresolvedWhenTelegramDoesNotExposeChatId() {
        client.invite = new TelegramClient.Invite(0, "Private channel", true);

        ChannelResolution result = adapter.resolveChannel(URI.create("https://t.me/+secret"));

        assertThat(result.sourceId()).isNull();
        assertThat(result.sourceData().path("inviteLink").asText()).isEqualTo("https://t.me/+secret");
        assertThat(result.accessState()).isEqualTo(AccessState.UNKNOWN);
    }

    @Test
    void reportsRequestedAccessForInviteThatRequiresApproval() {
        client.joinResult = new TelegramClient.JoinResult.Requested();

        var result = adapter.refreshAccess(unresolvedPrivateChannel());

        assertThat(result.state()).isEqualTo(AccessState.REQUESTED);
        assertThat(result.resolvedSourceId()).isNull();
    }

    @Test
    void grantsAccessAndReturnsResolvedIdAfterJoiningPrivateChannel() {
        client.joinResult = new TelegramClient.JoinResult.Joined(-10077L);

        var result = adapter.refreshAccess(unresolvedPrivateChannel());

        assertThat(result.state()).isEqualTo(AccessState.GRANTED);
        assertThat(result.resolvedSourceId()).isEqualTo("-10077");
    }

    @Test
    void revalidatesResolvedPrivateChannelMembership() {
        client.membership = TelegramClient.Membership.INACTIVE;
        client.joinResult = new TelegramClient.JoinResult.Declined();

        var result = adapter.refreshAccess(resolvedPrivateChannel());

        assertThat(result.state()).isEqualTo(AccessState.DENIED);
    }

    @Test
    void reportsRequestedAccessWhenResolvedPrivateChannelNeedsApprovalAgain() {
        client.membership = TelegramClient.Membership.INACTIVE;
        client.joinResult = new TelegramClient.JoinResult.Requested();

        var result = adapter.refreshAccess(resolvedPrivateChannel());

        assertThat(result.state()).isEqualTo(AccessState.REQUESTED);
    }

    @Test
    void revalidatesResolvedPublicChannelAndReturnsChangedStableId() {
        client.membership = TelegramClient.Membership.INACTIVE;
        client.publicChannel = new TelegramClient.Channel(-10088L, "News");

        var result = adapter.refreshAccess(resolvedChannel(SubscriptionState.INACTIVE));

        assertThat(client.resolvedUsername).isEqualTo("news");
        assertThat(result.state()).isEqualTo(AccessState.GRANTED);
        assertThat(result.resolvedSourceId()).isEqualTo("-10088");
    }

    @Test
    void reportsDeniedWhenResolvedPublicChannelNoLongerExists() {
        client.membership = TelegramClient.Membership.INACTIVE;
        client.resolveFailure = new SourcePermanentException("gone");

        var result = adapter.refreshAccess(resolvedChannel(SubscriptionState.INACTIVE));

        assertThat(result.state()).isEqualTo(AccessState.DENIED);
    }

    @Test
    void propagatesTransientFailureWhileRevalidatingResolvedAccess() {
        client.membershipFailure = new SourceTemporaryException("offline");

        assertThatThrownBy(() -> adapter.refreshAccess(resolvedChannel(SubscriptionState.INACTIVE)))
            .isSameAs(client.membershipFailure);
    }

    @Test
    void reportsActiveSubscriptionFromMembership() {
        client.membership = TelegramClient.Membership.ACTIVE;
        assertThat(adapter.refreshSubscription(resolvedChannel(SubscriptionState.INACTIVE)).state())
            .isEqualTo(SubscriptionState.ACTIVE);
    }

    @Test
    void startsInactiveSubscriptionAndReportsPendingApproval() {
        client.membership = TelegramClient.Membership.INACTIVE;
        client.joinResult = new TelegramClient.JoinResult.Requested();

        assertThat(adapter.refreshSubscription(resolvedChannel(SubscriptionState.INACTIVE)).state())
            .isEqualTo(SubscriptionState.ACTIVATING);
    }

    @Test
    void reportsLostOnlyWhenPreviouslyActiveMembershipWasMeaningfullyLost() {
        client.membership = TelegramClient.Membership.INACTIVE;

        assertThat(adapter.refreshSubscription(resolvedChannel(SubscriptionState.ACTIVE)).state())
            .isEqualTo(SubscriptionState.LOST);
    }

    @Test
    void propagatesTransientConnectivityInsteadOfReportingLost() {
        client.membershipFailure = new SourceTemporaryException("offline");

        assertThatThrownBy(() -> adapter.refreshSubscription(resolvedChannel(SubscriptionState.ACTIVE)))
            .isSameAs(client.membershipFailure);
    }

    @Test
    void exposesConfiguredTimingAndSourceType() {
        assertThat(adapter.type()).isEqualTo(SourceType.TELEGRAM);
        assertThat(adapter.groupingThreshold()).isEqualTo(Duration.ofMinutes(3));
        assertThat(adapter.settlingDelay()).isEqualTo(Duration.ofSeconds(20));
        assertThat(adapter.pollingInterval(resolvedChannel(SubscriptionState.ACTIVE)))
            .isEqualTo(Duration.ofMinutes(2));
        assertThat(adapter.pollingInterval(resolvedChannel(SubscriptionState.INACTIVE)))
            .isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void deliversRelevantLiveUpdatesSequentiallyAndIgnoresSkippedRemap() throws Exception {
        List<SourceEvent> events = new ArrayList<>();
        CountDownLatch delivered = new CountDownLatch(2);
        adapter.start(event -> {
            events.add(event);
            delivered.countDown();
        });

        client.emit(new TdApi.UpdateNewMessage(message(-10077, 2, "second")));
        client.emit(new TdApi.UpdateNewMessage(message(-10077, 3, null)));
        client.emit(new TdApi.UpdateNewMessage(message(-10077, 1, "first")));

        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(events).extracting(event -> ((SourceEvent.MessageChanged) event).message().sourceId())
            .containsExactly("2", "1");
        assertThat(meterRegistry.get("telegram_messages_skipped_total").counter().count()).isEqualTo(1);
    }

    @Test
    void rereadsChangedMessagesAndOnlyEmitsPermanentDeletions() throws Exception {
        client.messages.put(9L, message(-10077, 9, "updated"));
        List<SourceEvent> events = new ArrayList<>();
        CountDownLatch delivered = new CountDownLatch(2);
        adapter.start(event -> {
            events.add(event);
            delivered.countDown();
        });

        client.emit(new TdApi.UpdateMessageContent(-10077, 9, null));
        client.emit(new TdApi.UpdateDeleteMessages(-10077, new long[] {8}, false, false));
        client.emit(new TdApi.UpdateDeleteMessages(-10077, new long[] {7}, true, false));

        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(events.get(0)).isInstanceOf(SourceEvent.MessageChanged.class);
        assertThat(((SourceEvent.MessageChanged) events.get(0)).message().body()).isEqualTo("<p>updated</p>");
        assertThat(events.get(1)).isEqualTo(new SourceEvent.MessageDeleted(SourceType.TELEGRAM, "-10077", "7"));
    }

    @Test
    void emitsSubscriptionAndAccessLossForPrivateSupergroupMembershipTransition() throws Exception {
        List<SourceEvent> events = new ArrayList<>();
        CountDownLatch delivered = new CountDownLatch(2);
        adapter.start(event -> {
            events.add(event);
            delivered.countDown();
        });

        client.emit(supergroupUpdate(77, new TdApi.ChatMemberStatusMember(), "news"));
        client.emit(supergroupUpdate(77, new TdApi.ChatMemberStatusLeft()));

        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(events).containsExactly(
            new SourceEvent.SubscriptionLost(SourceType.TELEGRAM, "-1000000000077",
                JsonNodeFactory.instance.objectNode()),
            new SourceEvent.AccessLost(SourceType.TELEGRAM, "-1000000000077",
                JsonNodeFactory.instance.objectNode())
        );
    }

    @Test
    void emitsOnlySubscriptionLossForPublicSupergroupMembershipTransition() throws Exception {
        List<SourceEvent> events = new ArrayList<>();
        CountDownLatch delivered = new CountDownLatch(1);
        adapter.start(event -> {
            events.add(event);
            delivered.countDown();
        });

        client.emit(supergroupUpdate(77, new TdApi.ChatMemberStatusMember(), "news"));
        client.emit(supergroupUpdate(77, new TdApi.ChatMemberStatusLeft(), "news"));

        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(events).containsExactly(new SourceEvent.SubscriptionLost(
            SourceType.TELEGRAM, "-1000000000077", JsonNodeFactory.instance.objectNode()
        ));
    }

    @Test
    void downloadsFromTdlibCacheAndDeletesCacheCopy() throws Exception {
        Path cached = temporaryDirectory.resolve("cached.bin");
        Files.writeString(cached, "payload");
        client.remoteFile = new TelegramClient.RemoteFile(123);
        client.downloadedFile = new TelegramClient.DownloadedFile(123, cached);
        Path destination = temporaryDirectory.resolve("staging.bin");

        adapter.downloadMedia(resolvedChannel(SubscriptionState.ACTIVE), media(), destination);

        assertThat(Files.readString(destination)).isEqualTo("payload");
        assertThat(client.reacquiredRemoteId).isEqualTo("remote-1");
        assertThat(client.reacquiredFileType).isEqualTo("photo");
        assertThat(client.deletedFileId).isEqualTo(123);
    }

    @Test
    void keepsDaemonAliveButUnreadyWhenTelegramSessionIsNotReadyYet() {
        client.ready = false;
        TelegramAdapterLifecycle lifecycle = new TelegramAdapterLifecycle(adapter, client, event -> {
        }, properties());

        lifecycle.start();

        assertThat(lifecycle.isRunning()).isTrue();
        assertThat(client.isReady()).isFalse();
    }

    private TelegramProperties properties() {
        TelegramProperties properties = new TelegramProperties();
        properties.setReadinessTimeout(Duration.ofMillis(1));
        return properties;
    }

    private org.moera.importer.source.MediaSnapshot media() {
        return new org.moera.importer.source.MediaSnapshot(1, 2, "unique-1", 0, "image/jpeg", null, 7L,
            new byte[32], JsonNodeFactory.instance.objectNode().put("remoteId", "remote-1").put("fileType", "photo"));
    }

    private static TdApi.Message message(long chatId, long id, String text) {
        TdApi.Message message = new TdApi.Message();
        message.chatId = chatId;
        message.id = id;
        message.date = 1_700_000_000;
        message.authorSignature = "";
        message.content = text == null ? null
            : new TdApi.MessageText(new TdApi.FormattedText(text, new TdApi.TextEntity[0]), null, null);
        return message;
    }

    private static TdApi.UpdateSupergroup supergroupUpdate(long id, TdApi.ChatMemberStatus status,
                                                            String... usernames) {
        TdApi.Supergroup supergroup = new TdApi.Supergroup();
        supergroup.id = id;
        supergroup.status = status;
        supergroup.usernames = new TdApi.Usernames(usernames, new String[0], "", new String[0]);
        return new TdApi.UpdateSupergroup(supergroup);
    }

    private ChannelSnapshot unresolvedPrivateChannel() {
        return new ChannelSnapshot(1, SourceType.TELEGRAM, null, URI.create("https://t.me/+secret"),
            JsonNodeFactory.instance.objectNode().put("inviteLink", "https://t.me/+secret"), AccessState.UNKNOWN,
            JsonNodeFactory.instance.objectNode(), SubscriptionState.INACTIVE,
            JsonNodeFactory.instance.objectNode(), null);
    }

    private ChannelSnapshot resolvedChannel(SubscriptionState subscriptionState) {
        return new ChannelSnapshot(1, SourceType.TELEGRAM, "-10077", URI.create("https://t.me/news"),
            JsonNodeFactory.instance.objectNode().put("username", "news"), AccessState.GRANTED,
            JsonNodeFactory.instance.objectNode(),
            subscriptionState, JsonNodeFactory.instance.objectNode(), null);
    }

    private ChannelSnapshot resolvedPrivateChannel() {
        return new ChannelSnapshot(1, SourceType.TELEGRAM, "-10077", URI.create("https://t.me/+secret"),
            JsonNodeFactory.instance.objectNode().put("inviteLink", "https://t.me/+secret"), AccessState.GRANTED,
            JsonNodeFactory.instance.objectNode(), SubscriptionState.INACTIVE,
            JsonNodeFactory.instance.objectNode(), null);
    }

    private static class FakeTelegramClient implements TelegramClient {

        private Channel publicChannel;
        private Invite invite;
        private JoinResult joinResult;
        private Membership membership;
        private RuntimeException membershipFailure;
        private RuntimeException resolveFailure;
        private String resolvedUsername;
        private String inspectedInviteLink;
        private final Map<Long, TdApi.Message> messages = new ConcurrentHashMap<>();
        private Consumer<TdApi.Update> updateConsumer;
        private RemoteFile remoteFile;
        private DownloadedFile downloadedFile;
        private String reacquiredRemoteId;
        private String reacquiredFileType;
        private int deletedFileId;
        private boolean ready = true;

        @Override
        public Channel resolvePublicChannel(String username) {
            resolvedUsername = username;
            if (resolveFailure != null) {
                throw resolveFailure;
            }
            return publicChannel;
        }

        @Override
        public Invite inspectInvite(String inviteLink) {
            inspectedInviteLink = inviteLink;
            return invite;
        }

        @Override
        public JoinResult join(String sourceId, String inviteLink) {
            return joinResult;
        }

        @Override
        public Membership membership(long chatId) {
            if (membershipFailure != null) {
                throw membershipFailure;
            }
            return membership;
        }

        @Override
        public TelegramClient.HistoryPage history(long chatId, long fromMessageId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TdApi.Message message(long chatId, long messageId) {
            return messages.get(messageId);
        }

        @Override
        public TelegramClient.RemoteFile reacquire(String remoteId, String fileType) {
            reacquiredRemoteId = remoteId;
            reacquiredFileType = fileType;
            return remoteFile;
        }

        @Override
        public TelegramClient.DownloadedFile download(int fileId) {
            return downloadedFile;
        }

        @Override
        public void deleteCachedFile(int fileId) {
            deletedFileId = fileId;
        }

        @Override
        public void start(Consumer<TdApi.Update> updateConsumer) {
            this.updateConsumer = updateConsumer;
        }

        @Override
        public boolean awaitReady(Duration timeout) {
            return ready;
        }

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public void close() {
        }

        private void emit(TdApi.Update update) {
            updateConsumer.accept(update);
        }

    }

}
