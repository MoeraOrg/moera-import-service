package org.moera.importer.source.telegram;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import it.tdlight.jni.TdApi;
import org.moera.importer.source.ChannelMetadata;

public interface TelegramClient extends AutoCloseable {

    record Channel(long id, String title) {
    }

    record Invite(long chatId, String title, boolean createsJoinRequest) {
    }

    sealed interface JoinResult {

        record Joined(long chatId) implements JoinResult {
        }

        record Requested() implements JoinResult {
        }

        record Declined() implements JoinResult {
        }

    }

    enum Membership {
        ACTIVE,
        INACTIVE
    }

    record RemoteFile(int fileId) {
    }

    record DownloadedFile(int fileId, Path path) {
    }

    record HistoryPage(int totalCount, List<TdApi.Message> messages) {

        public HistoryPage {
            messages = List.copyOf(messages);
        }

    }

    Channel resolvePublicChannel(String username);

    Invite inspectInvite(String inviteLink);

    JoinResult join(String sourceId, String inviteLink);

    Membership membership(long chatId);

    ChannelMetadata channelMetadata(long chatId);

    HistoryPage history(long chatId, long fromMessageId, int limit);

    TdApi.Message message(long chatId, long messageId);

    RemoteFile reacquire(String remoteId, String fileType);

    DownloadedFile download(int fileId);

    void deleteCachedFile(int fileId);

    void start(Consumer<TdApi.Update> updateConsumer);

    default boolean awaitReady(Duration timeout) {
        return true;
    }

    default boolean isReady() {
        return true;
    }

    @Override
    void close();

}
