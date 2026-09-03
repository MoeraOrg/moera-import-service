package org.moera.importer.source.telegram;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.tdlight.jni.TdApi;
import org.moera.importer.config.TelegramProperties;
import org.moera.importer.model.AccessState;
import org.moera.importer.model.SourceType;
import org.moera.importer.model.SubscriptionState;
import org.moera.importer.observability.ImportMetrics;
import org.moera.importer.source.AccessResult;
import org.moera.importer.source.ChannelResolution;
import org.moera.importer.source.ChannelSnapshot;
import org.moera.importer.source.GroupingRelation;
import org.moera.importer.source.InitialImportMode;
import org.moera.importer.source.MediaSnapshot;
import org.moera.importer.source.PollResult;
import org.moera.importer.source.SourceAdapter;
import org.moera.importer.source.SourceEvent;
import org.moera.importer.source.SourceEventSink;
import org.moera.importer.source.SourceMessage;
import org.moera.importer.source.SourcePermanentException;
import org.moera.importer.source.SourceTemporaryException;
import org.moera.importer.source.SubscriptionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelegramSourceAdapter implements SourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(TelegramSourceAdapter.class);
    private static final int MAX_NO_PROGRESS_PAGES = 3;

    private final TelegramClient client;
    private final TelegramMessageMapper mapper;
    private final TelegramProperties properties;
    private final TelegramMediaDownloader mediaDownloader;
    private final BlockingQueue<TdApi.Update> updateQueue = new LinkedBlockingQueue<>();
    private final Map<Long, Boolean> activeSupergroupMembership = new LinkedHashMap<>();
    private ExecutorService updateExecutor;
    private boolean clientInitialized;
    private volatile boolean running;

    @Inject
    private ImportMetrics importMetrics;

    public TelegramSourceAdapter(TelegramClient client, TelegramMessageMapper mapper, TelegramProperties properties) {
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        mediaDownloader = new TelegramMediaDownloader(client);
    }

    @Override
    public SourceType type() {
        return SourceType.TELEGRAM;
    }

    @Override
    public ChannelResolution resolveChannel(URI sourceUri, InitialImportMode initialImportMode) {
        Objects.requireNonNull(initialImportMode, "initialImportMode");
        TelegramReference reference = TelegramReference.parse(sourceUri);
        ObjectNode sourceData = objectNode().put("initialImportMode", initialImportMode.name());
        if (reference instanceof TelegramReference.Invite inviteReference) {
            TelegramClient.Invite invite = client.inspectInvite(inviteReference.inviteLink());
            sourceData.put("inviteLink", inviteReference.inviteLink());
            sourceData.put("title", invite.title());
            String sourceId = invite.chatId() == 0 ? null : Long.toString(invite.chatId());
            AccessState access = sourceId == null ? AccessState.UNKNOWN : AccessState.GRANTED;
            SubscriptionState subscription = sourceId == null ? SubscriptionState.INACTIVE
                : subscriptionState(invite.chatId());
            return resolution(sourceId, inviteReference.canonicalUri(), sourceData, access, subscription);
        }
        TelegramReference.Public publicReference = (TelegramReference.Public) reference;
        TelegramClient.Channel channel = client.resolvePublicChannel(publicReference.username());
        if (channel == null || channel.id() == 0) {
            throw new SourcePermanentException("Telegram channel cannot be resolved");
        }
        sourceData.put("username", publicReference.username()).put("title", channel.title());
        return resolution(Long.toString(channel.id()), publicReference.canonicalUri(), sourceData,
            AccessState.GRANTED, subscriptionState(channel.id()));
    }

    private ChannelResolution resolution(String sourceId, URI uri, ObjectNode sourceData, AccessState access,
                                         SubscriptionState subscription) {
        return new ChannelResolution(sourceId, uri, sourceData, access, objectNode(), subscription,
            objectNode());
    }

    private SubscriptionState subscriptionState(long chatId) {
        return client.membership(chatId) == TelegramClient.Membership.ACTIVE
            ? SubscriptionState.ACTIVE : SubscriptionState.INACTIVE;
    }

    @Override
    public Duration groupingThreshold() {
        return properties.getGroupingThreshold();
    }

    @Override
    public Duration settlingDelay() {
        return properties.getSettlingDelay();
    }

    @Override
    public Duration pollingInterval(ChannelSnapshot channel) {
        return channel.subscriptionState() == SubscriptionState.ACTIVE
            ? properties.getPollingInterval() : properties.getInactivePollingInterval();
    }

    @Override
    public GroupingRelation groupingRelation(SourceMessage previous, SourceMessage next) {
        long previousAlbum = previous.sourceData().path("mediaAlbumId").asLong(0);
        long nextAlbum = next.sourceData().path("mediaAlbumId").asLong(0);
        if (previousAlbum != 0 && previousAlbum == nextAlbum) {
            return GroupingRelation.MUST_GROUP;
        }
        if (previousAlbum != 0 && nextAlbum != 0) {
            return GroupingRelation.MUST_SEPARATE;
        }
        return GroupingRelation.UNKNOWN;
    }

    @Override
    public AccessResult refreshAccess(ChannelSnapshot channel) {
        if (channel.sourceId() == null) {
            return accessResult(client.join(null, channel.sourceData().path("inviteLink").textValue()));
        }

        long chatId = requiredChatId(channel);
        if (client.membership(chatId) == TelegramClient.Membership.ACTIVE) {
            return new AccessResult(AccessState.GRANTED, null, objectNode());
        }

        String inviteLink = channel.sourceData().path("inviteLink").textValue();
        if (inviteLink != null) {
            return accessResult(client.join(channel.sourceId(), inviteLink));
        }

        String username = channel.sourceData().path("username").textValue();
        if (username == null) {
            return new AccessResult(AccessState.DENIED, null, objectNode());
        }
        try {
            TelegramClient.Channel resolved = client.resolvePublicChannel(username);
            if (resolved == null || resolved.id() == 0) {
                return new AccessResult(AccessState.DENIED, null, objectNode());
            }
            return new AccessResult(AccessState.GRANTED, Long.toString(resolved.id()), objectNode());
        } catch (SourcePermanentException e) {
            return new AccessResult(AccessState.DENIED, null, objectNode());
        }
    }

    private AccessResult accessResult(TelegramClient.JoinResult result) {
        return switch (result) {
            case TelegramClient.JoinResult.Joined joined -> new AccessResult(
                AccessState.GRANTED, Long.toString(joined.chatId()), objectNode());
            case TelegramClient.JoinResult.Requested ignored -> new AccessResult(
                AccessState.REQUESTED, null, objectNode());
            case TelegramClient.JoinResult.Declined ignored -> new AccessResult(
                AccessState.DENIED, null, objectNode());
        };
    }

    @Override
    public SubscriptionResult refreshSubscription(ChannelSnapshot channel) {
        long chatId = requiredChatId(channel);
        TelegramClient.Membership membership = client.membership(chatId);
        SubscriptionState state;
        if (membership == TelegramClient.Membership.ACTIVE) {
            state = SubscriptionState.ACTIVE;
        } else if (channel.subscriptionState() == SubscriptionState.ACTIVE) {
            state = SubscriptionState.LOST;
        } else {
            state = switch (client.join(channel.sourceId(), channel.sourceData().path("inviteLink").textValue())) {
                case TelegramClient.JoinResult.Joined ignored -> SubscriptionState.ACTIVE;
                case TelegramClient.JoinResult.Requested ignored -> SubscriptionState.ACTIVATING;
                case TelegramClient.JoinResult.Declined ignored -> SubscriptionState.FAILED;
            };
        }
        return new SubscriptionResult(state, objectNode());
    }

    @Override
    public PollResult poll(ChannelSnapshot channel) {
        long chatId = requiredChatId(channel);
        long previousWaterline = channel.waterline() == null ? 0 : channel.waterline().path("messageId").asLong(0);
        long fromMessageId = 0;
        long newestScannedId = previousWaterline;
        int initialLimit = previousWaterline == 0 && !isFullInitialImport(channel)
            ? properties.getInitialHistoryCount()
            : Integer.MAX_VALUE;
        int lookBehindRead = 0;
        int noProgressPages = 0;
        boolean complete = false;
        Map<Long, TdApi.Message> scanned = new LinkedHashMap<>();
        while (!complete) {
            int remaining = initialLimit - scanned.size();
            if (remaining <= 0) {
                break;
            }
            TelegramClient.HistoryPage historyPage = client.history(
                chatId, fromMessageId, Math.min(properties.getHistoryPageSize(), remaining)
            );
            List<TdApi.Message> page = historyPage.messages();
            if (page.isEmpty()) {
                break;
            }
            long oldestId = Long.MAX_VALUE;
            boolean progressed = false;
            for (TdApi.Message message : page) {
                if (fromMessageId != 0 && message.id >= fromMessageId) {
                    continue;
                }
                progressed = true;
                oldestId = Math.min(oldestId, message.id);
                if (previousWaterline != 0 && message.id < previousWaterline) {
                    lookBehindRead++;
                    if (lookBehindRead > properties.getLookBehindCount()) {
                        complete = true;
                        break;
                    }
                }
                if (scanned.putIfAbsent(message.id, message) == null) {
                    newestScannedId = Math.max(newestScannedId, message.id);
                    if (scanned.size() == initialLimit) {
                        complete = true;
                        break;
                    }
                }
            }
            if (complete) {
                break;
            }
            if (!progressed) {
                if (historyPage.totalCount() <= scanned.size()) {
                    break;
                }
                noProgressPages++;
                if (noProgressPages >= MAX_NO_PROGRESS_PAGES) {
                    throw new SourceTemporaryException("Telegram history pagination made no progress");
                }
                continue;
            }
            noProgressPages = 0;
            fromMessageId = oldestId;
        }

        List<SourceMessage> messages = new ArrayList<>();
        scanned.values().stream().sorted(Comparator.comparingLong(message -> message.id)).forEach(message -> {
            TelegramMappingResult result = mapper.map(message);
            if (result instanceof TelegramMappingResult.Mapped mapped) {
                messages.add(mapped.message());
            } else if (result instanceof TelegramMappingResult.Skipped skipped) {
                skipped(message, skipped.reason());
            }
        });
        return new PollResult(messages, objectNode().put("messageId", newestScannedId));
    }

    private boolean isFullInitialImport(ChannelSnapshot channel) {
        return InitialImportMode.FULL.name().equals(channel.sourceData().path("initialImportMode").asText());
    }

    @Override
    public void downloadMedia(ChannelSnapshot channel, MediaSnapshot media, Path destination) {
        requiredChatId(channel);
        mediaDownloader.download(media, destination);
    }

    @Override
    public synchronized void start(SourceEventSink sink) {
        Objects.requireNonNull(sink, "sink");
        if (running) {
            throw new IllegalStateException("Telegram adapter is already started");
        }
        initialize();
        running = true;
        updateExecutor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("telegram-updates-", 0).factory());
        updateExecutor.submit(() -> consumeUpdates(sink));
    }

    public synchronized void initialize() {
        if (clientInitialized) {
            return;
        }
        try {
            client.start(updateQueue::add);
            clientInitialized = true;
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (updateExecutor != null) {
            updateExecutor.shutdownNow();
            updateExecutor = null;
        }
        client.close();
        clientInitialized = false;
        updateQueue.clear();
    }

    private void consumeUpdates(SourceEventSink sink) {
        while (running) {
            try {
                handleUpdate(updateQueue.take(), sink);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("Failed to process Telegram update {}; polling will recover it",
                    e.getClass().getSimpleName());
            }
        }
    }

    private void handleUpdate(TdApi.Update update, SourceEventSink sink) {
        if (update instanceof TdApi.UpdateNewMessage changed) {
            emitMapped(changed.message, sink);
        } else if (update instanceof TdApi.UpdateMessageContent changed) {
            emitMapped(client.message(changed.chatId, changed.messageId), sink);
        } else if (update instanceof TdApi.UpdateMessageIsPinned changed) {
            emitMapped(client.message(changed.chatId, changed.messageId), sink);
        } else if (update instanceof TdApi.UpdateDeleteMessages deleted && deleted.isPermanent && !deleted.fromCache) {
            for (long messageId : deleted.messageIds) {
                sink.accept(new SourceEvent.MessageDeleted(
                    SourceType.TELEGRAM, Long.toString(deleted.chatId), Long.toString(messageId)
                ));
            }
        } else if (update instanceof TdApi.UpdateSupergroup changed) {
            handleSupergroup(changed.supergroup, sink);
        }
    }

    private void emitMapped(TdApi.Message message, SourceEventSink sink) {
        if (message == null) {
            return;
        }
        TelegramMappingResult result = mapper.map(message);
        if (result instanceof TelegramMappingResult.Mapped mapped) {
            sink.accept(new SourceEvent.MessageChanged(
                SourceType.TELEGRAM, Long.toString(message.chatId), mapped.message()
            ));
        } else if (result instanceof TelegramMappingResult.Skipped skipped) {
            skipped(message, skipped.reason());
        }
    }

    private void skipped(TdApi.Message message, TelegramSkipReason reason) {
        String contentFamily = contentFamily(message.content);
        importMetrics.telegramMessageSkipped(contentFamily, reason);
        log.info(
            "Skipped Telegram message: operation=telegram_map, channel_id={}, message_id={}, content_type={}, "
                + "result={}",
            message.chatId, message.id, contentFamily, reason
        );
    }

    private String contentFamily(TdApi.MessageContent content) {
        if (content == null) {
            return "invalid_content";
        }
        String name = content.getClass().getSimpleName();
        String base = name.startsWith("Message") ? name.substring("Message".length()) : name;
        return base.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    private void handleSupergroup(TdApi.Supergroup supergroup, SourceEventSink sink) {
        if (supergroup == null) {
            return;
        }
        boolean active = isActive(supergroup.status);
        Boolean previouslyActive = activeSupergroupMembership.put(supergroup.id, active);
        if (!Boolean.TRUE.equals(previouslyActive) || active) {
            return;
        }
        String chatId = Long.toString(-1_000_000_000_000L - supergroup.id);
        sink.accept(new SourceEvent.SubscriptionLost(
            SourceType.TELEGRAM, chatId, objectNode()
        ));
        if (supergroup.status instanceof TdApi.ChatMemberStatusBanned || !hasPublicUsername(supergroup)) {
            sink.accept(new SourceEvent.AccessLost(SourceType.TELEGRAM, chatId, objectNode()));
        }
    }

    private boolean hasPublicUsername(TdApi.Supergroup supergroup) {
        return supergroup.usernames != null
            && supergroup.usernames.activeUsernames != null
            && supergroup.usernames.activeUsernames.length > 0;
    }

    private boolean isActive(TdApi.ChatMemberStatus status) {
        return status instanceof TdApi.ChatMemberStatusCreator creator && creator.isMember
            || status instanceof TdApi.ChatMemberStatusAdministrator
            || status instanceof TdApi.ChatMemberStatusMember
            || status instanceof TdApi.ChatMemberStatusRestricted restricted && restricted.isMember;
    }

    private long requiredChatId(ChannelSnapshot channel) {
        if (channel.sourceId() == null) {
            throw new SourcePermanentException("Telegram channel has no resolved chat ID");
        }
        try {
            return Long.parseLong(channel.sourceId());
        } catch (NumberFormatException e) {
            throw new SourcePermanentException("Invalid Telegram chat ID", e);
        }
    }

    private static ObjectNode objectNode() {
        return JsonNodeFactory.instance.objectNode();
    }

}
