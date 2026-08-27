package org.moera.importer.source.telegram;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moera.importer.config.TelegramProperties;
import org.moera.importer.model.AccessState;
import org.moera.importer.model.SourceType;
import org.moera.importer.model.SubscriptionState;
import org.moera.importer.source.ChannelSnapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramPollingTest {

    private final HistoryClient client = new HistoryClient();
    private final TelegramProperties properties = new TelegramProperties();

    @BeforeEach
    void setUp() {
        properties.setLookBehindCount(2);
        properties.setHistoryPageSize(100);
    }

    @Test
    void paginatesAcrossShortPagesAndReturnsMessagesOldestFirst() {
        client.pages.add(page(5, message(5, "five"), message(4, "four")));
        client.pages.add(page(5, message(3, "three")));
        client.pages.add(page(5, message(2, "two"), message(1, "one")));
        client.pages.add(page(5));

        var result = adapter(new TelegramMessageMapper()).poll(channel(null));

        assertThat(result.messages()).extracting(message -> message.sourceId())
            .containsExactly("1", "2", "3", "4", "5");
        assertThat(result.waterline().path("messageId").asLong()).isEqualTo(5);
        assertThat(client.fromIds).containsExactly(0L, 4L, 3L, 1L);
    }

    @Test
    void retriesAnInclusiveBoundaryOnlyPageWhenOlderMessagesStillExist() {
        client.pages.add(page(5, message(5, "five"), message(4, "four")));
        client.pages.add(page(5, message(4, "four")));
        client.pages.add(page(5, message(4, "four"), message(3, "three")));
        client.pages.add(page(5, message(3, "three"), message(2, "two")));
        client.pages.add(page(5, message(2, "two"), message(1, "one")));
        client.pages.add(page(5, message(1, "one")));

        var result = adapter(new TelegramMessageMapper()).poll(channel(null));

        assertThat(result.messages()).extracting(message -> message.sourceId())
            .containsExactly("1", "2", "3", "4", "5");
        assertThat(result.waterline().path("messageId").asLong()).isEqualTo(5);
    }

    @Test
    void readsConfiguredLookBehindPastPreviousWaterline() {
        client.pages.add(page(12, message(12, "twelve"), message(11, "eleven")));
        client.pages.add(page(12, message(10, "ten"), message(9, "nine")));
        client.pages.add(page(12, message(8, "eight"), message(7, "seven")));

        var result = adapter(new TelegramMessageMapper()).poll(channel(10L));

        assertThat(result.messages()).extracting(message -> message.sourceId())
            .containsExactly("8", "9", "10", "11", "12");
        assertThat(result.waterline().path("messageId").asLong()).isEqualTo(12);
        assertThat(client.fromIds).containsExactly(0L, 11L, 9L);
    }

    @Test
    void skippedMessageIsOmittedButStillAdvancesWaterline() {
        client.pages.add(page(22, message(22, null), message(21, "kept")));
        client.pages.add(page(22));

        var result = adapter(new TelegramMessageMapper()).poll(channel(20L));

        assertThat(result.messages()).extracting(message -> message.sourceId()).containsExactly("21");
        assertThat(result.waterline().path("messageId").asLong()).isEqualTo(22);
    }

    @Test
    void unexpectedMapperFailurePropagates() {
        client.pages.add(page(1, message(1, "boom")));
        client.pages.add(page(1));
        TelegramMessageMapper failingMapper = new TelegramMessageMapper() {
            @Override
            public TelegramMappingResult map(TdApi.Message message) {
                throw new IllegalStateException("mapper failed");
            }
        };

        assertThatThrownBy(() -> adapter(failingMapper).poll(channel(null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("mapper failed");
    }

    private TelegramSourceAdapter adapter(TelegramMessageMapper mapper) {
        return new TelegramSourceAdapter(client, mapper, properties);
    }

    private ChannelSnapshot channel(Long waterline) {
        return new ChannelSnapshot(1, SourceType.TELEGRAM, "-1001", URI.create("https://t.me/news"),
            JsonNodeFactory.instance.objectNode(), AccessState.GRANTED, JsonNodeFactory.instance.objectNode(),
            SubscriptionState.ACTIVE, JsonNodeFactory.instance.objectNode(), waterline == null ? null
                : JsonNodeFactory.instance.objectNode().put("messageId", waterline));
    }

    private static TdApi.Message message(long id, String text) {
        TdApi.Message message = new TdApi.Message();
        message.id = id;
        message.chatId = -1001;
        message.date = 1_700_000_000 + (int) id;
        message.authorSignature = "";
        message.content = text == null ? null
            : new TdApi.MessageText(new TdApi.FormattedText(text, new TdApi.TextEntity[0]), null, null);
        return message;
    }

    private static TelegramClient.HistoryPage page(int totalCount, TdApi.Message... messages) {
        return new TelegramClient.HistoryPage(totalCount, List.of(messages));
    }

    private static class HistoryClient implements TelegramClient {

        private final Queue<TelegramClient.HistoryPage> pages = new ArrayDeque<>();
        private final List<Long> fromIds = new ArrayList<>();

        @Override
        public TelegramClient.HistoryPage history(long chatId, long fromMessageId, int limit) {
            fromIds.add(fromMessageId);
            return pages.remove();
        }

        @Override
        public Channel resolvePublicChannel(String username) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Invite inspectInvite(String inviteLink) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JoinResult join(String sourceId, String inviteLink) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Membership membership(long chatId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TdApi.Message message(long chatId, long messageId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RemoteFile reacquire(String remoteId, String fileType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DownloadedFile download(int fileId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteCachedFile(int fileId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void start(Consumer<TdApi.Update> updateConsumer) {
        }

        @Override
        public void close() {
        }

    }

}
