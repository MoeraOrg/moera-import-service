package org.moera.importer.application;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moera.importer.model.AccessState;
import org.moera.importer.model.BodyFormat;
import org.moera.importer.model.SourceType;
import org.moera.importer.model.SubscriptionState;
import org.moera.importer.observability.ImportMetrics;
import org.moera.importer.persistence.ChannelRepository;
import org.moera.importer.persistence.IngestResult;
import org.moera.importer.persistence.MessageRepository;
import org.moera.importer.source.ChannelSnapshot;
import org.moera.importer.source.ChannelMetadata;
import org.moera.importer.source.SourceEvent;
import org.moera.importer.source.SourceMessage;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SourceEventProcessorTest {

    private SourceEventProcessor sourceEventProcessor;
    private RecordingChannelRepository channelRepository;
    private RecordingMessageRepository messageRepository;
    private SimpleMeterRegistry meterRegistry;
    private ProfileSynchronizer profileSynchronizer;

    @BeforeEach
    void configureProcessor() {
        sourceEventProcessor = new SourceEventProcessor();
        channelRepository = new RecordingChannelRepository();
        messageRepository = new RecordingMessageRepository();
        meterRegistry = new SimpleMeterRegistry();
        ImportMetrics importMetrics = new ImportMetrics();
        ReflectionTestUtils.setField(importMetrics, "meterRegistry", meterRegistry);
        ReflectionTestUtils.setField(sourceEventProcessor, "channelRepository", channelRepository);
        ReflectionTestUtils.setField(sourceEventProcessor, "messageRepository", messageRepository);
        ReflectionTestUtils.setField(sourceEventProcessor, "importMetrics", importMetrics);
        profileSynchronizer = mock(ProfileSynchronizer.class);
        ReflectionTestUtils.setField(sourceEventProcessor, "profileSynchronizer", profileSynchronizer);
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
        assertThat(meterRegistry.get("message_revisions_total").tag("result", "created")
            .counter().count()).isEqualTo(1);
    }

    @Test
    void marksLiveDeletion() {
        sourceEventProcessor.accept(new SourceEvent.MessageDeleted(
            SourceType.TELEGRAM, "channel-1", "message-1"
        ));

        assertThat(messageRepository.deletedChannelId).isEqualTo(17);
        assertThat(messageRepository.deletedSourceId).isEqualTo("message-1");
        assertThat(meterRegistry.get("message_revisions_total").tag("result", "deleted")
            .counter().count()).isEqualTo(1);
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
    void persistsLiveMetadataAndSynchronizesProfile() {
        ChannelMetadata metadata = new ChannelMetadata("Renamed", "Description", "news", null);

        sourceEventProcessor.accept(new SourceEvent.ChannelMetadataChanged(
            SourceType.TELEGRAM, "channel-1", metadata
        ));

        assertThat(channelRepository.metadata).isEqualTo(metadata);
        verify(profileSynchronizer).synchronize(17);
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
        private ChannelMetadata metadata;

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

        @Override
        public boolean updateMetadata(SourceType sourceType, String sourceId, ChannelMetadata metadata) {
            this.metadata = metadata;
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
