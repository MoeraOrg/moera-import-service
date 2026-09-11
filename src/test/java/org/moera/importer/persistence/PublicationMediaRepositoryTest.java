package org.moera.importer.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import jakarta.inject.Inject;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moera.importer.application.FailureSeries;
import org.moera.importer.model.MediaSkipReason;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.moera.importer.persistence.jooq.enums.AccessState.GRANTED;
import static org.moera.importer.persistence.jooq.enums.PublicationMediaStatus.PENDING;
import static org.moera.importer.persistence.jooq.enums.PublicationMediaStatus.UPLOADED;
import static org.moera.importer.persistence.jooq.enums.SourceType.TELEGRAM;
import static org.moera.importer.persistence.jooq.enums.SubscriptionState.ACTIVE;
import static org.moera.importer.persistence.jooq.tables.Channels.CHANNELS;
import static org.moera.importer.persistence.jooq.tables.Destinations.DESTINATIONS;
import static org.moera.importer.persistence.jooq.tables.Media.MEDIA;
import static org.moera.importer.persistence.jooq.tables.Messages.MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Mirrors.MIRRORS;
import static org.moera.importer.persistence.jooq.tables.PublicationMedia.PUBLICATION_MEDIA;
import static org.moera.importer.persistence.jooq.tables.PublicationMessages.PUBLICATION_MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Publications.PUBLICATIONS;

@SpringBootTest
class PublicationMediaRepositoryTest extends PostgresIntegrationTest {

    @Inject
    private DSLContext dslContext;

    @Inject
    private JooqMappings jooqMappings;

    @Inject
    private PublicationMediaRepository publicationMediaRepository;

    @Inject
    private PlatformTransactionManager platformTransactionManager;

    @BeforeEach
    void clearDatabase() {
        dslContext.truncate(CHANNELS).restartIdentity().cascade().execute();
        dslContext.truncate(DESTINATIONS).restartIdentity().cascade().execute();
    }

    @Test
    void claimsOnlyDueRowsAndLoadsDeliveryContext() {
        Fixture fixture = insertFixture(hash(1));
        dslContext.update(PUBLICATION_MEDIA)
            .set(PUBLICATION_MEDIA.ATTEMPTS, 2)
            .where(PUBLICATION_MEDIA.PUBLICATION_ID.eq(fixture.publicationId()))
            .and(PUBLICATION_MEDIA.MEDIA_ID.eq(fixture.mediaId()))
            .execute();

        PublicationMediaClaim claim = publicationMediaRepository.claimPending(1, Duration.ofMinutes(5)).getFirst();
        PublicationMediaContext context = publicationMediaRepository.loadContext(
            claim.publicationId(), claim.mediaId()
        );

        assertThat(claim.publicationId()).isEqualTo(fixture.publicationId());
        assertThat(claim.mediaId()).isEqualTo(fixture.mediaId());
        assertThat(claim.attempts()).isEqualTo(3);
        assertThat(claim.processingToken()).isNotNull();
        assertThat(context.destination().nodeName()).isEqualTo("destination.example");
        assertThat(context.channel().sourceId()).isEqualTo("channel-1");
        assertThat(context.media().sourceId()).isEqualTo("media-1");
        assertThat(context.destinationMediaId()).isNull();
        assertThat(context.destinationHash()).isNull();
        assertThat(context.compressionPending()).isFalse();
        assertThat(publicationMediaRepository.claimPending(1, Duration.ofMinutes(5))).isEmpty();
    }

    @Test
    void logsExpiredLeaseReclaimWithOldAndNewClaimContext() {
        Fixture fixture = insertFixture(hash(1));
        UUID expiredToken = UUID.fromString("e52bbfc3-34b5-43ce-a9f3-44c3ef62349a");
        OffsetDateTime expiredAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        dslContext.update(PUBLICATION_MEDIA)
            .set(PUBLICATION_MEDIA.ATTEMPTS, 7)
            .set(PUBLICATION_MEDIA.PROCESSING_TOKEN, expiredToken)
            .set(PUBLICATION_MEDIA.PROCESSING_UNTIL, expiredAt)
            .where(PUBLICATION_MEDIA.PUBLICATION_ID.eq(fixture.publicationId()))
            .and(PUBLICATION_MEDIA.MEDIA_ID.eq(fixture.mediaId()))
            .execute();
        Logger logger = (Logger) LoggerFactory.getLogger(PublicationMediaRepository.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        PublicationMediaClaim claim;
        try {
            claim = publicationMediaRepository.claimPending(1, Duration.ofMinutes(5)).getFirst();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(
                "Publication media lease reclaimed",
                "publication_id=" + fixture.publicationId(),
                "media_id=" + fixture.mediaId(),
                "previous_processing_token=" + expiredToken,
                "processing_token=" + claim.processingToken(),
                "attempt=8"
            );
        });
    }

    @Test
    void doesNotLogExpiredLeaseReclaimWhenClaimTransactionRollsBack() {
        Fixture fixture = insertFixture(hash(1));
        UUID expiredToken = UUID.fromString("e52bbfc3-34b5-43ce-a9f3-44c3ef62349a");
        dslContext.update(PUBLICATION_MEDIA)
            .set(PUBLICATION_MEDIA.PROCESSING_TOKEN, expiredToken)
            .set(PUBLICATION_MEDIA.PROCESSING_UNTIL, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
            .where(PUBLICATION_MEDIA.PUBLICATION_ID.eq(fixture.publicationId()))
            .and(PUBLICATION_MEDIA.MEDIA_ID.eq(fixture.mediaId()))
            .execute();
        Logger logger = (Logger) LoggerFactory.getLogger(PublicationMediaRepository.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            new TransactionTemplate(platformTransactionManager).executeWithoutResult(status -> {
                publicationMediaRepository.claimPending(1, Duration.ofMinutes(5));
                status.setRollbackOnly();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
            .noneSatisfy(message -> assertThat(message).contains("Publication media lease reclaimed"));
    }

    @Test
    void compressionWaitIsNormalAndSourceHashChangeRejectsStaleCompletion() {
        byte[] originalHash = hash(1);
        Fixture fixture = insertFixture(originalHash);
        PublicationMediaClaim firstClaim = publicationMediaRepository.claimPending(1, Duration.ofMinutes(5)).getFirst();
        dslContext.update(PUBLICATION_MEDIA)
            .set(PUBLICATION_MEDIA.FAILURE_TYPE, "failure.Type")
            .set(PUBLICATION_MEDIA.FAILURE_STARTED_AT, OffsetDateTime.now(ZoneOffset.UTC).minusHours(1))
            .execute();
        Instant nextCheck = Instant.now().plusSeconds(10).truncatedTo(ChronoUnit.MICROS);

        boolean waiting = publicationMediaRepository.awaitingCompression(
            fixture.publicationId(),
            fixture.mediaId(),
            firstClaim.processingToken(),
            originalHash,
            "original-id",
            "original-hash",
            nextCheck
        );

        assertThat(waiting).isTrue();
        var waitingRecord = dslContext.selectFrom(PUBLICATION_MEDIA).fetchSingle();
        assertThat(waitingRecord.getStatus()).isEqualTo(PENDING);
        assertThat(waitingRecord.getDestinationMediaId()).isEqualTo("original-id");
        assertThat(waitingRecord.getDestinationHash()).isEqualTo("original-hash");
        assertThat(waitingRecord.getCompressionPending()).isTrue();
        assertThat(waitingRecord.getNextAttemptAt().toInstant()).isEqualTo(nextCheck);
        assertThat(waitingRecord.getLastError()).isNull();
        assertThat(waitingRecord.getFailureType()).isNull();
        assertThat(waitingRecord.getFailureStartedAt()).isNull();
        assertThat(waitingRecord.getProcessingToken()).isNull();

        dslContext.update(PUBLICATION_MEDIA)
            .set(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT, OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1))
            .execute();
        PublicationMediaClaim staleClaim = publicationMediaRepository.claimPending(1, Duration.ofMinutes(5)).getFirst();
        dslContext.update(MEDIA)
            .set(MEDIA.SOURCE_HASH, hash(2))
            .where(MEDIA.ID.eq(fixture.mediaId()))
            .execute();
        publicationMediaRepository.synchronizeForMessage(fixture.messageId(), List.of(fixture.mediaId()));

        boolean uploaded = publicationMediaRepository.uploaded(
            fixture.publicationId(),
            fixture.mediaId(),
            staleClaim.processingToken(),
            originalHash,
            "compressed-id",
            "compressed-hash"
        );

        assertThat(uploaded).isFalse();
        var resetRecord = dslContext.selectFrom(PUBLICATION_MEDIA).fetchSingle();
        assertThat(resetRecord.getDestinationMediaId()).isNull();
        assertThat(resetRecord.getDestinationHash()).isNull();
        assertThat(resetRecord.getCompressionPending()).isFalse();
        assertThat(resetRecord.getAttempts()).isZero();
        assertThat(resetRecord.getProcessingToken()).isNull();
    }

    @Test
    void terminalAndFailureTransitionsRequireMatchingToken() {
        Fixture fixture = insertFixture(hash(1));
        PublicationMediaClaim claim = publicationMediaRepository.claimPending(1, Duration.ofMinutes(5)).getFirst();

        assertThat(publicationMediaRepository.skipped(
            fixture.publicationId(),
            fixture.mediaId(),
            java.util.UUID.randomUUID(),
            hash(1),
            MediaSkipReason.TOO_LARGE
        )).isFalse();
        assertThat(publicationMediaRepository.retry(
            fixture.publicationId(),
            fixture.mediaId(),
            claim.processingToken(),
            Instant.now().plusSeconds(30),
            "temporary",
            new FailureSeries("failure.Type", Instant.now())
        )).isTrue();
        var retryRecord = dslContext.selectFrom(PUBLICATION_MEDIA).fetchSingle();
        assertThat(retryRecord.getLastError()).isEqualTo("temporary");
        assertThat(retryRecord.getFailureType()).isEqualTo("failure.Type");
        assertThat(retryRecord.getFailureStartedAt()).isNotNull();
        assertThat(retryRecord.getProcessingToken()).isNull();

        dslContext.update(PUBLICATION_MEDIA)
            .set(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT, OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1))
            .execute();
        claim = publicationMediaRepository.claimPending(1, Duration.ofMinutes(5)).getFirst();
        assertThat(claim.failureSeries().type()).isEqualTo("failure.Type");
        assertThat(publicationMediaRepository.fail(
            fixture.publicationId(), fixture.mediaId(), claim.processingToken(), "permanent", null
        )).isTrue();
        var failedRecord = dslContext.selectFrom(PUBLICATION_MEDIA).fetchSingle();
        assertThat(failedRecord.getNextAttemptAt()).isNull();
        assertThat(failedRecord.getLastError()).isEqualTo("permanent");
        assertThat(failedRecord.getFailureType()).isNull();
        assertThat(failedRecord.getFailureStartedAt()).isNull();
        assertThat(failedRecord.getProcessingToken()).isNull();
    }

    @Test
    void stagingRetentionRequiresCurrentUploadPendingStateAndSourceHash() {
        byte[] originalHash = hash(1);
        byte[] changedHash = hash(2);
        Fixture fixture = insertFixture(originalHash);
        StagingMediaKey originalKey = new StagingMediaKey(
            fixture.publicationId(), fixture.mediaId(), HexFormat.of().formatHex(originalHash)
        );
        StagingMediaKey changedKey = new StagingMediaKey(
            fixture.publicationId(), fixture.mediaId(), HexFormat.of().formatHex(changedHash)
        );

        assertThat(publicationMediaRepository.findUploadPending(List.of(originalKey)))
            .containsExactly(originalKey);

        dslContext.update(PUBLICATION_MEDIA)
            .set(PUBLICATION_MEDIA.DESTINATION_MEDIA_ID, "original-id")
            .execute();

        assertThat(publicationMediaRepository.findUploadPending(List.of(originalKey))).isEmpty();

        dslContext.update(PUBLICATION_MEDIA)
            .setNull(PUBLICATION_MEDIA.DESTINATION_MEDIA_ID)
            .execute();
        dslContext.update(MEDIA)
            .set(MEDIA.SOURCE_HASH, changedHash)
            .execute();

        assertThat(publicationMediaRepository.findUploadPending(List.of(originalKey, changedKey)))
            .containsExactly(changedKey);

        dslContext.update(PUBLICATION_MEDIA)
            .set(PUBLICATION_MEDIA.STATUS, UPLOADED)
            .execute();

        assertThat(publicationMediaRepository.findUploadPending(List.of(changedKey))).isEmpty();
    }

    private Fixture insertFixture(byte[] sourceHash) {
        long channelId = dslContext.insertInto(CHANNELS)
            .set(CHANNELS.SOURCE_TYPE, TELEGRAM)
            .set(CHANNELS.SOURCE_ID, "channel-1")
            .set(CHANNELS.SOURCE_URI, "https://t.me/channel-1")
            .set(CHANNELS.ACCESS_STATE, GRANTED)
            .set(CHANNELS.SUBSCRIPTION_STATE, ACTIVE)
            .returningResult(CHANNELS.ID)
            .fetchSingle(CHANNELS.ID);
        long messageId = dslContext.insertInto(MESSAGES)
            .set(MESSAGES.CHANNEL_ID, channelId)
            .set(MESSAGES.SOURCE_ID, "message-1")
            .set(MESSAGES.PUBLISHED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(MESSAGES.SOURCE_BODY_HASH, hash(3))
            .returningResult(MESSAGES.ID)
            .fetchSingle(MESSAGES.ID);
        long mediaId = dslContext.insertInto(MEDIA)
            .set(MEDIA.MESSAGE_ID, messageId)
            .set(MEDIA.SOURCE_ID, "media-1")
            .set(MEDIA.POSITION, 0)
            .set(MEDIA.MIME_TYPE, "image/jpeg")
            .set(MEDIA.FILE_NAME, "media.jpg")
            .set(MEDIA.SIZE, 100L)
            .set(MEDIA.SOURCE_HASH, sourceHash)
            .returningResult(MEDIA.ID)
            .fetchSingle(MEDIA.ID);
        long destinationId = dslContext.insertInto(DESTINATIONS)
            .set(DESTINATIONS.NODE_NAME, "destination.example")
            .returningResult(DESTINATIONS.ID)
            .fetchSingle(DESTINATIONS.ID);
        long mirrorId = dslContext.insertInto(MIRRORS)
            .set(MIRRORS.CHANNEL_ID, channelId)
            .set(MIRRORS.DESTINATION_ID, destinationId)
            .set(MIRRORS.FEEDS, new String[] { "timeline" })
            .returningResult(MIRRORS.ID)
            .fetchSingle(MIRRORS.ID);
        long publicationId = dslContext.insertInto(PUBLICATIONS)
            .set(PUBLICATIONS.MIRROR_ID, mirrorId)
            .returningResult(PUBLICATIONS.ID)
            .fetchSingle(PUBLICATIONS.ID);
        dslContext.insertInto(PUBLICATION_MESSAGES)
            .set(PUBLICATION_MESSAGES.PUBLICATION_ID, publicationId)
            .set(PUBLICATION_MESSAGES.MESSAGE_ID, messageId)
            .set(PUBLICATION_MESSAGES.POSITION, 0)
            .execute();
        dslContext.insertInto(PUBLICATION_MEDIA)
            .set(PUBLICATION_MEDIA.PUBLICATION_ID, publicationId)
            .set(PUBLICATION_MEDIA.MEDIA_ID, mediaId)
            .set(PUBLICATION_MEDIA.STATUS, PENDING)
            .set(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT, OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1))
            .execute();
        return new Fixture(publicationId, messageId, mediaId);
    }

    private byte[] hash(int firstByte) {
        byte[] hash = new byte[32];
        hash[0] = (byte) firstByte;
        return hash;
    }

    private record Fixture(long publicationId, long messageId, long mediaId) {
    }

}
