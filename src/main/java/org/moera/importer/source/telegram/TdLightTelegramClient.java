package org.moera.importer.source.telegram;

import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import it.tdlight.ClientFactory;
import it.tdlight.client.APIToken;
import it.tdlight.client.TDLibSettings;
import it.tdlight.client.TelegramError;
import it.tdlight.jni.TdApi;
import org.moera.importer.config.TelegramProperties;
import org.moera.importer.source.ChannelMetadata;
import org.moera.importer.source.SourcePermanentException;
import org.moera.importer.source.SourceTemporaryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ObjectUtils;

public class TdLightTelegramClient implements TelegramClient, TelegramForwardMetadataResolver {

    private static final Logger log = LoggerFactory.getLogger(TdLightTelegramClient.class);

    private final TelegramProperties properties;
    private final boolean interactive;
    private final Supplier<ClientFactory> clientFactorySupplier;
    private final Supplier<TdLightLoginPrompt> loginPromptSupplier;
    private ClientFactory factory;
    private it.tdlight.TelegramClient client;
    private TdLightAuthorization authorization;
    private TdLightLoginPrompt loginPrompt;
    private volatile long selfUserId;

    public TdLightTelegramClient(TelegramProperties properties, boolean interactive) {
        this(properties, interactive, ClientFactory::create, TdLightConsoleLoginPrompt::new);
    }

    TdLightTelegramClient(TelegramProperties properties, boolean interactive,
                          Supplier<ClientFactory> clientFactorySupplier,
                          Supplier<TdLightLoginPrompt> loginPromptSupplier) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.interactive = interactive;
        this.clientFactorySupplier = Objects.requireNonNull(clientFactorySupplier, "clientFactorySupplier");
        this.loginPromptSupplier = Objects.requireNonNull(loginPromptSupplier, "loginPromptSupplier");
    }

    @Override
    public synchronized void start(Consumer<TdApi.Update> updateConsumer) {
        if (client != null) {
            throw new IllegalStateException("TDLight client is already started");
        }
        validateConfiguration();
        try {
            Files.createDirectories(properties.getDatabaseDirectory());
            Files.createDirectories(properties.getDownloadCacheDirectory());
        } catch (java.io.IOException e) {
            throw new SourcePermanentException("Cannot create TDLight storage directories", e);
        }
        TDLibSettings settings = TDLibSettings.create(new APIToken(properties.getApiId(), properties.getApiHash()));
        settings.setDatabaseDirectoryPath(properties.getDatabaseDirectory());
        settings.setDownloadedFilesDirectoryPath(properties.getDownloadCacheDirectory());
        factory = clientFactorySupplier.get();
        client = factory.createClient();
        loginPrompt = interactive ? loginPromptSupplier.get() : null;
        authorization = new TdLightAuthorization(settings, interactive, this::sendAuthorization, loginPrompt);
        client.initialize(
            (it.tdlight.UpdatesHandler) updates -> handleUpdates(updates, updateConsumer),
            this::handleClientException,
            this::handleClientException
        );
    }

    private void handleUpdates(List<TdApi.Object> updates, Consumer<TdApi.Update> updateConsumer) {
        for (TdApi.Object object : updates) {
            if (object instanceof TdApi.Update update) {
                if (update instanceof TdApi.UpdateAuthorizationState authorizationUpdate) {
                    authorization.handle(authorizationUpdate.authorizationState);
                }
                updateConsumer.accept(update);
            } else if (object instanceof TdApi.Error error) {
                authorization.fail(new TelegramError(error));
            }
        }
    }

    private void sendAuthorization(TdApi.Function<?> function) {
        client.send(function, result -> {
            if (result instanceof TdApi.Error error) {
                authorization.fail(new TelegramError(error));
            }
        }, authorization::fail);
    }

    private void handleClientException(Throwable failure) {
        authorization.fail(failure);
        log.warn("TDLight client callback failed", failure);
    }

    @Override
    public boolean awaitReady(Duration timeout) {
        if (authorization == null) {
            return false;
        }
        try {
            authorization.ready().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            return false;
        }
    }

    @Override
    public boolean isReady() {
        return authorization != null && authorization.isReady();
    }

    @Override
    public Channel resolvePublicChannel(String username) {
        try {
            TdApi.Chat chat = send(new TdApi.SearchPublicChat(username));
            return new Channel(chat.id, chat.title);
        } catch (SourceTemporaryException e) {
            throw permanentClientInput("Telegram public channel cannot be resolved", e);
        }
    }

    @Override
    public Source user(long userId) {
        TdApi.User user = send(new TdApi.GetUser(userId));
        String name;
        if (ObjectUtils.isEmpty(user.firstName)) {
            name = user.lastName;
        } else if (ObjectUtils.isEmpty(user.lastName)) {
            name = user.firstName;
        } else {
            name = user.firstName + " " + user.lastName;
        }
        return new Source(name, primaryUsername(user.usernames));
    }

    @Override
    public Source chat(long chatId) {
        TdApi.Chat chat = send(new TdApi.GetChat(chatId));
        String username = null;
        if (chat.type instanceof TdApi.ChatTypeSupergroup supergroupType) {
            TdApi.Supergroup supergroup = send(new TdApi.GetSupergroup(supergroupType.supergroupId));
            username = primaryUsername(supergroup.usernames);
        }
        return new Source(chat.title, username);
    }

    @Override
    public String messageLink(long chatId, long messageId) {
        String publicLink = TelegramForwardMetadataResolver.super.messageLink(chatId, messageId);
        if (!ObjectUtils.isEmpty(publicLink)) {
            return publicLink;
        }
        TdApi.MessageLink link = send(new TdApi.GetMessageLink(chatId, messageId, 0, 0, "", false, false));
        return link == null ? null : link.link;
    }

    private String primaryUsername(TdApi.Usernames usernames) {
        return usernames == null || ObjectUtils.isEmpty(usernames.activeUsernames)
            ? null
            : usernames.activeUsernames[0];
    }

    @Override
    public Invite inspectInvite(String inviteLink) {
        try {
            TdApi.ChatInviteLinkInfo info = send(new TdApi.CheckChatInviteLink(inviteLink));
            return new Invite(info.chatId, info.title, info.createsJoinRequest);
        } catch (SourceTemporaryException e) {
            throw permanentClientInput("Telegram invite link is invalid or unavailable", e);
        }
    }

    @Override
    public JoinResult join(String sourceId, String inviteLink) {
        TdApi.ChatJoinResult result;
        try {
            if (sourceId != null) {
                result = send(new TdApi.JoinChat(parseChatId(sourceId)));
            } else if (inviteLink != null) {
                result = send(new TdApi.JoinChatByInviteLink(inviteLink));
            } else {
                throw new SourcePermanentException("Telegram channel has neither chat ID nor invite link");
            }
        } catch (SourceTemporaryException e) {
            if (isPermanentClientError(e)) {
                return new JoinResult.Declined();
            }
            throw e;
        }
        return switch (result) {
            case TdApi.ChatJoinResultSuccess success -> new JoinResult.Joined(success.chatId);
            case TdApi.ChatJoinResultRequestSent ignored -> new JoinResult.Requested();
            case TdApi.ChatJoinResultGuardBotApprovalRequired ignored -> new JoinResult.Requested();
            case TdApi.ChatJoinResultDeclined ignored -> new JoinResult.Declined();
            default -> throw new SourcePermanentException(
                "Unsupported Telegram join result: " + result.getClass().getSimpleName()
            );
        };
    }

    @Override
    public Membership membership(long chatId) {
        TdApi.Chat chat = send(new TdApi.GetChat(chatId));
        TdApi.ChatMemberStatus status;
        if (chat.type instanceof TdApi.ChatTypeSupergroup supergroupType) {
            status = send(new TdApi.GetSupergroup(supergroupType.supergroupId)).status;
        } else {
            status = send(new TdApi.GetChatMember(chatId, new TdApi.MessageSenderUser(selfUserId()))).status;
        }
        return isActive(status) ? Membership.ACTIVE : Membership.INACTIVE;
    }

    @Override
    public ChannelMetadata channelMetadata(long chatId) {
        TdApi.Chat chat = send(new TdApi.GetChat(chatId));
        String username = null;
        String description = "";
        if (chat.type instanceof TdApi.ChatTypeSupergroup supergroupType) {
            TdApi.Supergroup supergroup = send(new TdApi.GetSupergroup(supergroupType.supergroupId));
            username = primaryUsername(supergroup.usernames);
            TdApi.SupergroupFullInfo fullInfo = send(new TdApi.GetSupergroupFullInfo(supergroupType.supergroupId));
            description = fullInfo.description;
        }
        ChannelMetadata.Emblem emblem = emblem(chat.photo);
        return new ChannelMetadata(
            ObjectUtils.isEmpty(chat.title) ? "" : chat.title,
            ObjectUtils.isEmpty(description) ? "" : description,
            descriptionEntities(description),
            username,
            emblem
        );
    }

    private List<ChannelMetadata.DescriptionEntity> descriptionEntities(String description) {
        if (ObjectUtils.isEmpty(description)) {
            return List.of();
        }
        TdApi.TextEntities detected = send(new TdApi.GetTextEntities(description));
        if (detected == null || detected.entities == null) {
            return List.of();
        }
        return Arrays.stream(detected.entities)
            .map(this::descriptionEntity)
            .filter(Objects::nonNull)
            .toList();
    }

    private ChannelMetadata.DescriptionEntity descriptionEntity(TdApi.TextEntity entity) {
        ChannelMetadata.DescriptionEntityType type = switch (entity.type) {
            case TdApi.TextEntityTypeUrl ignored -> ChannelMetadata.DescriptionEntityType.URL;
            case TdApi.TextEntityTypeEmailAddress ignored -> ChannelMetadata.DescriptionEntityType.EMAIL;
            case TdApi.TextEntityTypeMention ignored -> ChannelMetadata.DescriptionEntityType.MENTION;
            default -> null;
        };
        return type != null ? new ChannelMetadata.DescriptionEntity(entity.offset, entity.length, type) : null;
    }

    private ChannelMetadata.Emblem emblem(TdApi.ChatPhotoInfo photo) {
        if (photo == null || photo.big == null || photo.big.remote == null
            || ObjectUtils.isEmpty(photo.big.remote.id) || ObjectUtils.isEmpty(photo.big.remote.uniqueId)) {
            return null;
        }
        return new ChannelMetadata.Emblem(photo.big.remote.id, photo.big.remote.uniqueId, "image/jpeg");
    }

    private long selfUserId() {
        long current = selfUserId;
        if (current == 0) {
            current = send(new TdApi.GetMe()).id;
            selfUserId = current;
        }
        return current;
    }

    @Override
    public HistoryPage history(long chatId, long fromMessageId, int limit) {
        TdApi.Messages messages = send(new TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false));
        List<TdApi.Message> page = messages.messages == null ? List.of() : Arrays.asList(messages.messages);
        return new HistoryPage(messages.totalCount, page);
    }

    @Override
    public TdApi.Message message(long chatId, long messageId) {
        return send(new TdApi.GetMessage(chatId, messageId));
    }

    @Override
    public RemoteFile reacquire(String remoteId, String fileType) {
        TdApi.File file = send(new TdApi.GetRemoteFile(remoteId, fileType(fileType)));
        return new RemoteFile(file.id);
    }

    @Override
    public DownloadedFile download(int fileId) {
        TdApi.File file = send(new TdApi.DownloadFile(fileId, 32, 0, 0, true));
        if (file.local == null || !file.local.isDownloadingCompleted || file.local.path == null) {
            throw new SourceTemporaryException("TDLight did not complete media download");
        }
        return new DownloadedFile(file.id, java.nio.file.Path.of(file.local.path));
    }

    @Override
    public void deleteCachedFile(int fileId) {
        send(new TdApi.DeleteFile(fileId));
    }

    @Override
    public synchronized void close() {
        try {
            if (client != null) {
                client.sendAsync(new TdApi.Close()).join();
                if (authorization != null) {
                    authorization.closed().orTimeout(90, TimeUnit.SECONDS).join();
                }
            }
        } finally {
            client = null;
            try {
                if (loginPrompt != null) {
                    loginPrompt.close();
                }
            } finally {
                loginPrompt = null;
                try {
                    if (factory != null) {
                        factory.close();
                    }
                } finally {
                    factory = null;
                    authorization = null;
                }
            }
        }
    }

    private <R extends TdApi.Object> R send(TdApi.Function<R> function) {
        if (client == null) {
            throw new IllegalStateException("TDLight client is not started");
        }
        try {
            return client.sendAsync(function).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            String result = cause != null && cause.getMessage() != null
                ? cause.getMessage()
                : cause != null ? cause.getClass().getSimpleName() : e.getClass().getSimpleName();
            throw new SourceTemporaryException(
                "Telegram operation %s failed: %s".formatted(function.getClass().getSimpleName(), result),
                cause != null ? cause : e
            );
        }
    }

    private TdApi.FileType fileType(String name) {
        return switch (name) {
            case "photo", "linkPreviewImage" -> new TdApi.FileTypePhoto();
            case "profilePhoto" -> new TdApi.FileTypeProfilePhoto();
            case "video" -> new TdApi.FileTypeVideo();
            case "document" -> new TdApi.FileTypeDocument();
            case "audio" -> new TdApi.FileTypeAudio();
            case "voiceNote" -> new TdApi.FileTypeVoiceNote();
            case "videoNote" -> new TdApi.FileTypeVideoNote();
            case "animation" -> new TdApi.FileTypeAnimation();
            case "sticker" -> new TdApi.FileTypeSticker();
            case "thumbnail" -> new TdApi.FileTypeThumbnail();
            default -> throw new SourcePermanentException("Unsupported Telegram file type: " + name);
        };
    }

    private boolean isActive(TdApi.ChatMemberStatus status) {
        return status instanceof TdApi.ChatMemberStatusCreator creator && creator.isMember
            || status instanceof TdApi.ChatMemberStatusAdministrator
            || status instanceof TdApi.ChatMemberStatusMember
            || status instanceof TdApi.ChatMemberStatusRestricted restricted && restricted.isMember;
    }

    private long parseChatId(String sourceId) {
        try {
            return Long.parseLong(sourceId);
        } catch (NumberFormatException e) {
            throw new SourcePermanentException("Invalid Telegram chat ID", e);
        }
    }

    private void validateConfiguration() {
        if (properties.getApiId() <= 0 || properties.getApiHash().isBlank()) {
            throw new SourcePermanentException("Telegram API ID and hash must be configured");
        }
    }

    private RuntimeException permanentClientInput(String message, SourceTemporaryException failure) {
        return isPermanentClientError(failure) ? new SourcePermanentException(message, failure) : failure;
    }

    private boolean isPermanentClientError(SourceTemporaryException failure) {
        Throwable cause = failure.getCause();
        return cause instanceof TelegramError error && error.getErrorCode() >= 400 && error.getErrorCode() < 500
            && error.getErrorCode() != 429;
    }

}
