package org.moera.importer.application;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moera.importer.model.AccessState;
import org.moera.importer.model.BodyFormat;
import org.moera.importer.model.SourceType;
import org.moera.importer.model.SubscriptionState;
import org.moera.importer.persistence.ChannelRepository;
import org.moera.importer.persistence.IngestResult;
import org.moera.importer.persistence.MessageRepository;
import org.moera.importer.source.ChannelSnapshot;
import org.moera.importer.source.SourceEvent;
import org.moera.importer.source.SourceMessage;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class SourceEventProcessorTest {

    private SourceEventProcessor sourceEventProcessor;
    private RecordingChannelRepository channelRepository;
    private RecordingMessageRepository messageRepository;

    @BeforeEach
    void configureProcessor() {
        sourceEventProcessor = new SourceEventProcessor();
        channelRepository = new RecordingChannelRepository();
        messageRepository = new RecordingMessageRepository();
        ReflectionTestUtils.setField(sourceEventProcessor, "channelRepository", channelRepository);
        ReflectionTestUtils.setField(sourceEventProcessor, "messageRepository", messageRepository);
    }

    @Test
    void ingestsLiveMessageWithoutAdvancingWaterline() {
        SourceMessage message = sourceMessage();

        sourceEventProcessor.accept(new SourceEvent.MessageChanged(
            SourceType.TELEGRAM, "channel-1", message
        ));

        assertThat(messageRepository.ingestedChannelId).isEqualTo(17);
        assertThat(messageRepository.ingestedMessage).isEqualTo(message);
        assertThat(messageRepository.pollIngested).isFalse();
    }

    @Test
    void marksLiveDeletion() {
        sourceEventProcessor.accept(new SourceEvent.MessageDeleted(
            SourceType.TELEGRAM, "channel-1", "message-1"
        ));

        assertThat(messageRepository.deletedChannelId).isEqualTo(17);
        assertThat(messageRepository.deletedSourceId).isEqualTo("message-1");
    }

    @Test
    void mapsLossEventsToChannelStateUpdates() {
        JsonNode accessData = JsonNodeFactory.instance.objectNode().put("access", "lost");
        JsonNode subscriptionData = JsonNodeFactory.instance.objectNode().put("subscription", "lost");

        sourceEventProcessor.accept(new SourceEvent.AccessLost(
            SourceType.TELEGRAM, "channel-1", accessData
        ));
        sourceEventProcessor.accept(new SourceEvent.SubscriptionLost(
            SourceType.TELEGRAM, "channel-1", subscriptionData
        ));

        assertThat(channelRepository.accessLostData).isEqualTo(accessData);
        assertThat(channelRepository.subscriptionLostData).isEqualTo(subscriptionData);
    }

    @Test
    void ignoresEventsForUnknownChannel() {
        channelRepository.known = false;

        sourceEventProcessor.accept(new SourceEvent.MessageChanged(
            SourceType.TELEGRAM, "unknown", sourceMessage()
        ));

        assertThat(messageRepository.ingestedMessage).isNull();
    }

    private SourceMessage sourceMessage() {
        return new SourceMessage(
            "message-1",
            false,
            false,
            Instant.parse("2026-08-26T11:00:00Z"),
            "body",
            BodyFormat.PLAIN_TEXT,
            new byte[32],
            JsonNodeFactory.instance.objectNode(),
            List.of(),
            List.of()
        );
    }

    private static class RecordingChannelRepository extends ChannelRepository {

        private boolean known = true;
        private JsonNode accessLostData;
        private JsonNode subscriptionLostData;

        @Override
        public java.util.Optional<ChannelSnapshot> find(SourceType sourceType, String sourceId) {
            return known ? java.util.Optional.of(channel()) : java.util.Optional.empty();
        }

        @Override
        public boolean markAccessLost(SourceType sourceType, String sourceId, JsonNode data) {
            accessLostData = data;
            return known;
        }

        @Override
        public boolean markSubscriptionLost(SourceType sourceType, String sourceId, JsonNode data) {
            subscriptionLostData = data;
            return known;
        }

        private ChannelSnapshot channel() {
            var empty = JsonNodeFactory.instance.objectNode();
            return new ChannelSnapshot(
                17,
                SourceType.TELEGRAM,
                "channel-1",
                URI.create("https://t.me/channel-1"),
                empty,
                AccessState.GRANTED,
                empty,
                SubscriptionState.ACTIVE,
                empty,
                empty
            );
        }

    }

    private static class RecordingMessageRepository extends MessageRepository {

        private long ingestedChannelId;
        private SourceMessage ingestedMessage;
        private boolean pollIngested;
        private long deletedChannelId;
        private String deletedSourceId;

        @Override
        public IngestResult ingest(long channelId, SourceMessage message) {
            ingestedChannelId = channelId;
            ingestedMessage = message;
            return new IngestResult(1, 0, true, true);
        }

        @Override
        public boolean markDeleted(long channelId, String sourceMessageId) {
            deletedChannelId = channelId;
            deletedSourceId = sourceMessageId;
            return true;
        }

        @Override
        public boolean ingestPoll(
            long channelId,
            java.util.UUID processingToken,
            org.moera.importer.source.PollResult result,
            Instant nextPollAt
        ) {
            pollIngested = true;
            return true;
        }

    }

}
