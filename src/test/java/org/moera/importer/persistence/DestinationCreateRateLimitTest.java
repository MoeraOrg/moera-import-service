package org.moera.importer.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import jakarta.inject.Inject;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.moera.importer.persistence.jooq.enums.AccessState.GRANTED;
import static org.moera.importer.persistence.jooq.enums.SourceType.TELEGRAM;
import static org.moera.importer.persistence.jooq.enums.SubscriptionState.ACTIVE;
import static org.moera.importer.persistence.jooq.tables.Channels.CHANNELS;
import static org.moera.importer.persistence.jooq.tables.Destinations.DESTINATIONS;
import static org.moera.importer.persistence.jooq.tables.Mirrors.MIRRORS;
import static org.moera.importer.persistence.jooq.tables.Publications.PUBLICATIONS;

@SpringBootTest
class DestinationCreateRateLimitTest extends PostgresIntegrationTest {

    @Inject
    private DestinationRepository destinationRepository;

    @Inject
    private DSLContext dslContext;

    @Inject
    private PublicationRepository publicationRepository;

    @BeforeEach
    void clearDatabase() {
        dslContext.truncate(CHANNELS).restartIdentity().cascade().execute();
        dslContext.truncate(DESTINATIONS).restartIdentity().cascade().execute();
    }

    @Test
    void concurrentCallsAcquireOnlyOneSlotForDestination() throws Exception {
        long destinationId = insertDestination("first-node");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<DestinationCreateSlot> first = executor.submit(() -> acquire(destinationId, start));
            Future<DestinationCreateSlot> second = executor.submit(() -> acquire(destinationId, start));
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                .filteredOn(DestinationCreateSlot.Acquired.class::isInstance)
                .hasSize(1);
            assertThat(List.of(first.get(), second.get()))
                .filteredOn(DestinationCreateSlot.Deferred.class::isInstance)
                .hasSize(1);
        }

        assertThat(dslContext.select(DESTINATIONS.NEXT_CREATE_AT)
            .from(DESTINATIONS)
            .where(DESTINATIONS.ID.eq(destinationId))
            .fetchSingle(DESTINATIONS.NEXT_CREATE_AT)).isNotNull();
        assertThat(destinationRepository.acquireCreateSlot(destinationId, Duration.ofMinutes(1)))
            .isInstanceOf(DestinationCreateSlot.Deferred.class);
    }

    @Test
    void differentDestinationsAcquireSlotsIndependently() {
        long first = insertDestination("first-node");
        long second = insertDestination("second-node");

        DestinationCreateSlot firstSlot = destinationRepository.acquireCreateSlot(first, Duration.ofMinutes(1));
        DestinationCreateSlot secondSlot = destinationRepository.acquireCreateSlot(second, Duration.ofMinutes(1));

        assertThat(firstSlot).isInstanceOf(DestinationCreateSlot.Acquired.class);
        assertThat(secondSlot).isInstanceOf(DestinationCreateSlot.Acquired.class);
    }

    @Test
    void createRateDeferralReversesClaimAttemptAndPreservesError() {
        UUID token = UUID.randomUUID();
        long publicationId = insertPublication(token);
        Instant retryAt = Instant.now().plus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.MICROS);

        boolean deferred = publicationRepository.deferCreateRate(publicationId, token, retryAt);

        assertThat(deferred).isTrue();
        var publication = dslContext.selectFrom(PUBLICATIONS)
            .where(PUBLICATIONS.ID.eq(publicationId))
            .fetchSingle();
        assertThat(publication.getAttempts()).isEqualTo(2);
        assertThat(publication.getNextAttemptAt().toInstant()).isEqualTo(retryAt);
        assertThat(publication.getLastError()).isEqualTo("previous failure");
        assertThat(publication.getProcessingToken()).isNull();
        assertThat(publication.getProcessingUntil()).isNull();
    }

    @Test
    void staleTokenCannotDeferCreateRate() {
        UUID token = UUID.randomUUID();
        long publicationId = insertPublication(token);

        boolean deferred = publicationRepository.deferCreateRate(
            publicationId, UUID.randomUUID(), Instant.now().plus(Duration.ofMinutes(1))
        );

        assertThat(deferred).isFalse();
        var publication = dslContext.selectFrom(PUBLICATIONS)
            .where(PUBLICATIONS.ID.eq(publicationId))
            .fetchSingle();
        assertThat(publication.getAttempts()).isEqualTo(3);
        assertThat(publication.getLastError()).isEqualTo("previous failure");
        assertThat(publication.getProcessingToken()).isEqualTo(token);
        assertThat(publication.getProcessingUntil()).isNotNull();
    }

    private DestinationCreateSlot acquire(long destinationId, CountDownLatch start) throws InterruptedException {
        start.await();
        return destinationRepository.acquireCreateSlot(destinationId, Duration.ofMinutes(1));
    }

    private long insertDestination(String nodeName) {
        return dslContext.insertInto(DESTINATIONS)
            .set(DESTINATIONS.NODE_NAME, nodeName)
            .returningResult(DESTINATIONS.ID)
            .fetchSingle(DESTINATIONS.ID);
    }

    private long insertPublication(UUID token) {
        long channelId = dslContext.insertInto(CHANNELS)
            .set(CHANNELS.SOURCE_TYPE, TELEGRAM)
            .set(CHANNELS.SOURCE_ID, "channel-1")
            .set(CHANNELS.SOURCE_URI, "https://t.me/channel-1")
            .set(CHANNELS.ACCESS_STATE, GRANTED)
            .set(CHANNELS.SUBSCRIPTION_STATE, ACTIVE)
            .returningResult(CHANNELS.ID)
            .fetchSingle(CHANNELS.ID);
        long destinationId = insertDestination("publication-node");
        long mirrorId = dslContext.insertInto(MIRRORS)
            .set(MIRRORS.CHANNEL_ID, channelId)
            .set(MIRRORS.DESTINATION_ID, destinationId)
            .returningResult(MIRRORS.ID)
            .fetchSingle(MIRRORS.ID);
        return dslContext.insertInto(PUBLICATIONS)
            .set(PUBLICATIONS.MIRROR_ID, mirrorId)
            .set(PUBLICATIONS.ATTEMPTS, 3)
            .set(PUBLICATIONS.NEXT_ATTEMPT_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(PUBLICATIONS.LAST_ERROR, "previous failure")
            .set(PUBLICATIONS.PROCESSING_TOKEN, token)
            .set(PUBLICATIONS.PROCESSING_UNTIL, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5))
            .returningResult(PUBLICATIONS.ID)
            .fetchSingle(PUBLICATIONS.ID);
    }

}
