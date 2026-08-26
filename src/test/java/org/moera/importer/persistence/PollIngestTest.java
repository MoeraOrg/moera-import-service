package org.moera.importer.persistence;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moera.importer.model.BodyFormat;
import org.moera.importer.model.SourceType;
import org.moera.importer.source.ChannelSnapshot;
import org.moera.importer.source.PollResult;
import org.moera.importer.source.SourceMessage;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.moera.importer.persistence.jooq.enums.AccessState.GRANTED;
import static org.moera.importer.persistence.jooq.enums.ChannelOperation.POLL;
import static org.moera.importer.persistence.jooq.enums.SubscriptionState.ACTIVE;
import static org.moera.importer.persistence.jooq.tables.Channels.CHANNELS;
import static org.moera.importer.persistence.jooq.tables.Messages.MESSAGES;

@SpringBootTest
class PollIngestTest extends PostgresIntegrationTest {

    @Inject
    private DSLContext dslContext;

    @Inject
    private ChannelRepository channelRepository;

    @Inject
    private JooqMappings jooqMappings;

    @Inject
    private MessageRepository messageRepository;

    @BeforeEach
    void clearDatabase() {
        dslContext.truncate(CHANNELS).restartIdentity().cascade().execute();
    }

    @Test
    void findsChannelByIdAndSourceIdentity() {
        UUID processingToken = UUID.randomUUID();
        long channelId = insertLeasedChannel(processingToken, "initial-waterline");

        ChannelSnapshot byId = channelRepository.find(channelId).orElseThrow();
        ChannelSnapshot bySource = channelRepository.find(SourceType.TELEGRAM, "channel-1").orElseThrow();

        assertThat(byId).isEqualTo(bySource);
        assertThat(byId.id()).isEqualTo(channelId);
        assertThat(byId.sourceType()).isEqualTo(SourceType.TELEGRAM);
        assertThat(byId.sourceUri()).isEqualTo(URI.create("https://t.me/channel-1"));
        assertThat(byId.sourceData().path("source").asText()).isEqualTo("data");
        assertThat(byId.accessData().path("access").asText()).isEqualTo("data");
        assertThat(byId.subscriptionData().path("subscription").asText()).isEqualTo("data");
        assertThat(byId.waterline().path("cursor").asText()).isEqualTo("initial-waterline");
        assertThat(channelRepository.find(SourceType.TELEGRAM, "missing-channel")).isEmpty();
    }

    @Test
    void ingestsPollAndCompletesMatchingLeaseAtomically() {
        UUID processingToken = UUID.randomUUID();
        long channelId = insertLeasedChannel(processingToken, "initial-waterline");
        Instant nextPollAt = Instant.parse("2026-08-25T10:30:00Z");
        PollResult result = new PollResult(
            List.of(sourceMessage()),
            JsonNodeFactory.instance.objectNode().put("cursor", "advanced-waterline")
        );

        boolean completed = messageRepository.ingestPoll(channelId, processingToken, result, nextPollAt);

        assertThat(completed).isTrue();
        assertThat(dslContext.fetchCount(MESSAGES)).isEqualTo(1);
        var channel = dslContext.selectFrom(CHANNELS).fetchSingle();
        assertThat(jooqMappings.jsonNode(channel.getWaterline()).path("cursor").asText())
            .isEqualTo("advanced-waterline");
        assertThat(channel.getLastPollAt()).isNotNull();
        assertThat(channel.getNextPollAt().toInstant()).isEqualTo(nextPollAt);
        assertThat(channel.getPollAttempts()).isZero();
        assertThat(channel.getPollLastError()).isNull();
        assertThat(channel.getProcessingOperation()).isNull();
        assertThat(channel.getProcessingToken()).isNull();
        assertThat(channel.getProcessingUntil()).isNull();
    }

    @Test
    void rejectsStalePollWithoutPersistingMessagesOrWaterline() {
        UUID processingToken = UUID.randomUUID();
        long channelId = insertLeasedChannel(processingToken, "initial-waterline");
        PollResult result = new PollResult(
            List.of(sourceMessage()),
            JsonNodeFactory.instance.objectNode().put("cursor", "advanced-waterline")
        );

        boolean completed = messageRepository.ingestPoll(
            channelId,
            UUID.randomUUID(),
            result,
            Instant.parse("2026-08-25T10:30:00Z")
        );

        assertThat(completed).isFalse();
        assertThat(dslContext.fetchCount(MESSAGES)).isZero();
        var channel = dslContext.selectFrom(CHANNELS).fetchSingle();
        assertThat(jooqMappings.jsonNode(channel.getWaterline()).path("cursor").asText())
            .isEqualTo("initial-waterline");
        assertThat(channel.getProcessingToken()).isEqualTo(processingToken);
        assertThat(channel.getPollAttempts()).isEqualTo(3);
        assertThat(channel.getPollLastError()).isEqualTo("previous failure");
    }

    @Test
    void rollsBackWholePollWhenLaterMessageCannotBePersisted() {
        UUID processingToken = UUID.randomUUID();
        long channelId = insertLeasedChannel(processingToken, "initial-waterline");
        SourceMessage invalidMessage = new SourceMessage(
            "message-2",
            false,
            Instant.parse("2026-08-25T10:16:30Z"),
            "Invalid hash length",
            BodyFormat.PLAIN_TEXT,
            new byte[31],
            JsonNodeFactory.instance.objectNode(),
            List.of()
        );
        PollResult result = new PollResult(
            List.of(sourceMessage(), invalidMessage),
            JsonNodeFactory.instance.objectNode().put("cursor", "advanced-waterline")
        );

        assertThatThrownBy(() -> messageRepository.ingestPoll(
            channelId,
            processingToken,
            result,
            Instant.parse("2026-08-25T10:30:00Z")
        )).isInstanceOf(RuntimeException.class);

        assertThat(dslContext.fetchCount(MESSAGES)).isZero();
        var channel = dslContext.selectFrom(CHANNELS).fetchSingle();
        assertThat(jooqMappings.jsonNode(channel.getWaterline()).path("cursor").asText())
            .isEqualTo("initial-waterline");
        assertThat(channel.getProcessingToken()).isEqualTo(processingToken);
    }

    private long insertLeasedChannel(UUID processingToken, String waterline) {
        return dslContext.insertInto(CHANNELS)
            .set(CHANNELS.SOURCE_TYPE, org.moera.importer.persistence.jooq.enums.SourceType.TELEGRAM)
            .set(CHANNELS.SOURCE_ID, "channel-1")
            .set(CHANNELS.SOURCE_URI, "https://t.me/channel-1")
            .set(CHANNELS.SOURCE_DATA, jooqMappings.json(
                JsonNodeFactory.instance.objectNode().put("source", "data")
            ))
            .set(CHANNELS.ACCESS_STATE, GRANTED)
            .set(CHANNELS.ACCESS_DATA, jooqMappings.json(
                JsonNodeFactory.instance.objectNode().put("access", "data")
            ))
            .set(CHANNELS.SUBSCRIPTION_STATE, ACTIVE)
            .set(CHANNELS.SUBSCRIPTION_DATA, jooqMappings.json(
                JsonNodeFactory.instance.objectNode().put("subscription", "data")
            ))
            .set(CHANNELS.WATERLINE, jooqMappings.json(
                JsonNodeFactory.instance.objectNode().put("cursor", waterline)
            ))
            .set(CHANNELS.POLL_ATTEMPTS, 3)
            .set(CHANNELS.POLL_LAST_ERROR, "previous failure")
            .set(CHANNELS.PROCESSING_OPERATION, POLL)
            .set(CHANNELS.PROCESSING_TOKEN, processingToken)
            .set(CHANNELS.PROCESSING_UNTIL, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5))
            .returningResult(CHANNELS.ID)
            .fetchSingle(CHANNELS.ID);
    }

    private SourceMessage sourceMessage() {
        byte[] bodyHash = new byte[32];
        bodyHash[0] = 1;
        return new SourceMessage(
            "message-1",
            false,
            Instant.parse("2026-08-25T10:15:30Z"),
            "Hello, Moera",
            BodyFormat.HTML,
            bodyHash,
            JsonNodeFactory.instance.objectNode(),
            List.of()
        );
    }

}
