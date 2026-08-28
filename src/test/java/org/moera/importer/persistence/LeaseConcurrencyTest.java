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
import org.moera.importer.model.AccessState;
import org.moera.importer.model.SubscriptionState;
import org.moera.importer.source.AccessResult;
import org.moera.importer.source.SubscriptionResult;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.moera.importer.persistence.jooq.enums.AccessState.GRANTED;
import static org.moera.importer.persistence.jooq.enums.AccessState.DENIED;
import static org.moera.importer.persistence.jooq.enums.AccessState.UNKNOWN;
import static org.moera.importer.persistence.jooq.enums.ChannelOperation.ACCESS;
import static org.moera.importer.persistence.jooq.enums.ChannelOperation.POLL;
import static org.moera.importer.persistence.jooq.enums.SubscriptionState.INACTIVE;
import static org.moera.importer.persistence.jooq.enums.SubscriptionState.LOST;
import static org.moera.importer.persistence.jooq.enums.SourceType.TELEGRAM;
import static org.moera.importer.persistence.jooq.enums.SubscriptionState.ACTIVE;
import static org.moera.importer.persistence.jooq.tables.Channels.CHANNELS;

@SpringBootTest
class LeaseConcurrencyTest extends PostgresIntegrationTest {

    @Inject
    private ChannelRepository channelRepository;

    @Inject
    private DSLContext dslContext;

    @BeforeEach
    void clearDatabase() {
        dslContext.truncate(CHANNELS).restartIdentity().cascade().execute();
    }

    @Test
    void onlyOneWorkerClaimsChannel() throws Exception {
        insertDuePoll();
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<ChannelClaim>> first = executor.submit(() -> claimPoll(start));
            Future<List<ChannelClaim>> second = executor.submit(() -> claimPoll(start));
            start.countDown();

            assertThat(first.get().size() + second.get().size()).isEqualTo(1);
        }
    }

    @Test
    void expiredLeaseIsReclaimedWithNewTokenAndIncrementedAttempt() {
        UUID expiredToken = UUID.randomUUID();
        long channelId = insertExpiredPoll(expiredToken);

        ChannelClaim claim = channelRepository.claimPolls(1, Duration.ofMinutes(5)).getFirst();

        assertThat(claim.channel().id()).isEqualTo(channelId);
        assertThat(claim.processingToken()).isNotEqualTo(expiredToken);
        assertThat(claim.attempts()).isEqualTo(4);
        var channel = dslContext.selectFrom(CHANNELS).fetchSingle();
        assertThat(channel.getProcessingToken()).isEqualTo(claim.processingToken());
        assertThat(channel.getPollAttempts()).isEqualTo(4);
    }

    @Test
    void staleCompletionCannotOverwriteReclaimedAccess() {
        UUID expiredToken = UUID.randomUUID();
        long channelId = insertExpiredAccess(expiredToken);
        ChannelClaim claim = channelRepository.claimAccess(1, Duration.ofMinutes(5)).getFirst();
        AccessResult staleResult = new AccessResult(
            AccessState.GRANTED,
            "resolved-channel",
            JsonNodeFactory.instance.objectNode().put("stale", true)
        );

        boolean completed = channelRepository.completeAccess(channelId, expiredToken, staleResult);

        assertThat(completed).isFalse();
        var channel = dslContext.selectFrom(CHANNELS).fetchSingle();
        assertThat(channel.getAccessState()).isEqualTo(UNKNOWN);
        assertThat(channel.getSourceId()).isNull();
        assertThat(channel.getProcessingToken()).isEqualTo(claim.processingToken());
        assertThat(channel.getAccessAttempts()).isEqualTo(3);
    }

    @Test
    void matchingAccessCompletionResetsAttemptAndClearsLease() {
        long channelId = insertExpiredAccess(UUID.randomUUID());
        ChannelClaim claim = channelRepository.claimAccess(1, Duration.ofMinutes(5)).getFirst();
        AccessResult result = new AccessResult(
            AccessState.GRANTED,
            "resolved-channel",
            JsonNodeFactory.instance.objectNode().put("member", true)
        );

        boolean completed = channelRepository.completeAccess(channelId, claim.processingToken(), result);

        assertThat(completed).isTrue();
        var channel = dslContext.selectFrom(CHANNELS).fetchSingle();
        assertThat(channel.getAccessState()).isEqualTo(GRANTED);
        assertThat(channel.getSourceId()).isEqualTo("resolved-channel");
        assertThat(channel.getAccessAttempts()).isZero();
        assertThat(channel.getAccessRetryAt()).isNull();
        assertThat(channel.getProcessingToken()).isNull();
    }

    @Test
    void matchingSubscriptionCompletionResetsAttemptAndClearsLease() {
        insertDueSubscription();
        ChannelClaim claim = channelRepository.claimSubscriptions(1, Duration.ofMinutes(5)).getFirst();
        SubscriptionResult result = new SubscriptionResult(
            SubscriptionState.ACTIVE,
            JsonNodeFactory.instance.objectNode().put("active", true)
        );

        boolean completed = channelRepository.completeSubscription(
            claim.channel().id(), claim.processingToken(), result
        );

        assertThat(completed).isTrue();
        var channel = dslContext.selectFrom(CHANNELS).fetchSingle();
        assertThat(channel.getSubscriptionState()).isEqualTo(ACTIVE);
        assertThat(channel.getSubscriptionAttempts()).isZero();
        assertThat(channel.getSubscriptionRetryAt()).isNull();
        assertThat(channel.getProcessingToken()).isNull();
    }

    @Test
    void temporaryPollFailureKeepsAttemptAndSchedulesRetry() {
        insertDuePoll();
        ChannelClaim claim = channelRepository.claimPolls(1, Duration.ofMinutes(5)).getFirst();
        Instant nextAttemptAt = Instant.now().plus(Duration.ofMinutes(5)).truncatedTo(ChronoUnit.MICROS);

        boolean failed = channelRepository.failPoll(
            claim.channel().id(), claim.processingToken(), nextAttemptAt, "temporary"
        );

        assertThat(failed).isTrue();
        var channel = dslContext.selectFrom(CHANNELS).fetchSingle();
        assertThat(channel.getPollAttempts()).isEqualTo(1);
        assertThat(channel.getNextPollAt().toInstant()).isEqualTo(nextAttemptAt);
        assertThat(channel.getPollLastError()).isEqualTo("temporary");
        assertThat(channel.getProcessingToken()).isNull();
    }

    @Test
    void matchingLivePollTokenExtendsLeaseWithoutChangingAttempts() {
        insertDuePoll();
        ChannelClaim claim = channelRepository.claimPolls(1, Duration.ofSeconds(30)).getFirst();
        OffsetDateTime before = dslContext.selectFrom(CHANNELS).fetchSingle().getProcessingUntil();

        boolean renewed = channelRepository.renewPollLease(
            claim.channel().id(), claim.processingToken(), Duration.ofMinutes(5)
        );

        var channel = dslContext.selectFrom(CHANNELS).fetchSingle();
        assertThat(renewed).isTrue();
        assertThat(channel.getProcessingUntil()).isAfter(before);
        assertThat(channel.getPollAttempts()).isEqualTo(claim.attempts());
    }

    @Test
    void wrongPollTokenCannotRenewLease() {
        insertDuePoll();
        ChannelClaim claim = channelRepository.claimPolls(1, Duration.ofMinutes(5)).getFirst();
        OffsetDateTime before = dslContext.selectFrom(CHANNELS).fetchSingle().getProcessingUntil();

        boolean renewed = channelRepository.renewPollLease(
            claim.channel().id(), UUID.randomUUID(), Duration.ofMinutes(10)
        );

        assertThat(renewed).isFalse();
        assertThat(dslContext.selectFrom(CHANNELS).fetchSingle().getProcessingUntil()).isEqualTo(before);
    }

    @Test
    void expiredPollLeaseCannotBeRenewed() {
        insertDuePoll();
        ChannelClaim claim = channelRepository.claimPolls(1, Duration.ofMinutes(5)).getFirst();
        OffsetDateTime expired = OffsetDateTime.now(ZoneOffset.UTC)
            .minusSeconds(1)
            .truncatedTo(ChronoUnit.MICROS);
        dslContext.update(CHANNELS)
            .set(CHANNELS.PROCESSING_UNTIL, expired)
            .execute();

        boolean renewed = channelRepository.renewPollLease(
            claim.channel().id(), claim.processingToken(), Duration.ofMinutes(10)
        );

        assertThat(renewed).isFalse();
        assertThat(dslContext.selectFrom(CHANNELS).fetchSingle().getProcessingUntil()).isEqualTo(expired);
    }

    @Test
    void concurrentReclaimCannotBeOverwrittenByOldRenewal() throws Exception {
        insertDuePoll();
        ChannelClaim oldClaim = channelRepository.claimPolls(1, Duration.ofMinutes(5)).getFirst();
        dslContext.update(CHANNELS)
            .set(CHANNELS.PROCESSING_UNTIL, OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1))
            .execute();
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> renewal = executor.submit(() -> {
                start.await();
                return channelRepository.renewPollLease(
                    oldClaim.channel().id(), oldClaim.processingToken(), Duration.ofMinutes(10)
                );
            });
            Future<List<ChannelClaim>> reclaim = executor.submit(() -> claimPoll(start));
            start.countDown();

            ChannelClaim newClaim = reclaim.get().getFirst();
            assertThat(renewal.get()).isFalse();
            var channel = dslContext.selectFrom(CHANNELS).fetchSingle();
            assertThat(channel.getProcessingToken()).isEqualTo(newClaim.processingToken());
            assertThat(channel.getProcessingUntil()).isAfter(OffsetDateTime.now(ZoneOffset.UTC));
        }
    }

    @Test
    void accessLossInvalidatesLeaseAndRearmsDependentWork() {
        UUID token = UUID.randomUUID();
        insertExpiredPoll(token);

        boolean updated = channelRepository.markAccessLost(
            org.moera.importer.model.SourceType.TELEGRAM,
            "channel-1",
            JsonNodeFactory.instance.objectNode().put("lost", true)
        );

        assertThat(updated).isTrue();
        var channel = dslContext.selectFrom(CHANNELS).fetchSingle();
        assertThat(channel.getAccessState()).isEqualTo(DENIED);
        assertThat(channel.getAccessRetryAt()).isNotNull();
        assertThat(channel.getSubscriptionState()).isEqualTo(LOST);
        assertThat(channel.getSubscriptionRetryAt()).isNotNull();
        assertThat(channel.getNextPollAt()).isNotNull();
        assertThat(channel.getPollAttempts()).isZero();
        assertThat(channel.getProcessingToken()).isNull();
    }

    @Test
    void subscriptionLossInvalidatesLeaseAndRearmsPolling() {
        insertExpiredPoll(UUID.randomUUID());

        boolean updated = channelRepository.markSubscriptionLost(
            org.moera.importer.model.SourceType.TELEGRAM,
            "channel-1",
            JsonNodeFactory.instance.objectNode().put("lost", true)
        );

        assertThat(updated).isTrue();
        var channel = dslContext.selectFrom(CHANNELS).fetchSingle();
        assertThat(channel.getSubscriptionState()).isEqualTo(LOST);
        assertThat(channel.getSubscriptionRetryAt()).isNotNull();
        assertThat(channel.getNextPollAt()).isNotNull();
        assertThat(channel.getPollAttempts()).isZero();
        assertThat(channel.getProcessingToken()).isNull();
    }

    private List<ChannelClaim> claimPoll(CountDownLatch start) throws InterruptedException {
        start.await();
        return channelRepository.claimPolls(1, Duration.ofMinutes(5));
    }

    private void insertDuePoll() {
        dslContext.insertInto(CHANNELS)
            .set(CHANNELS.SOURCE_TYPE, TELEGRAM)
            .set(CHANNELS.SOURCE_ID, "channel-1")
            .set(CHANNELS.SOURCE_URI, "https://t.me/channel-1")
            .set(CHANNELS.ACCESS_STATE, GRANTED)
            .set(CHANNELS.SUBSCRIPTION_STATE, ACTIVE)
            .set(CHANNELS.NEXT_POLL_AT, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
            .execute();
    }

    private long insertExpiredPoll(UUID processingToken) {
        return dslContext.insertInto(CHANNELS)
            .set(CHANNELS.SOURCE_TYPE, TELEGRAM)
            .set(CHANNELS.SOURCE_ID, "channel-1")
            .set(CHANNELS.SOURCE_URI, "https://t.me/channel-1")
            .set(CHANNELS.ACCESS_STATE, GRANTED)
            .set(CHANNELS.SUBSCRIPTION_STATE, ACTIVE)
            .set(CHANNELS.NEXT_POLL_AT, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2))
            .set(CHANNELS.POLL_ATTEMPTS, 3)
            .set(CHANNELS.PROCESSING_OPERATION, POLL)
            .set(CHANNELS.PROCESSING_TOKEN, processingToken)
            .set(CHANNELS.PROCESSING_UNTIL, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
            .returningResult(CHANNELS.ID)
            .fetchSingle(CHANNELS.ID);
    }

    private void insertDueSubscription() {
        dslContext.insertInto(CHANNELS)
            .set(CHANNELS.SOURCE_TYPE, TELEGRAM)
            .set(CHANNELS.SOURCE_ID, "channel-1")
            .set(CHANNELS.SOURCE_URI, "https://t.me/channel-1")
            .set(CHANNELS.ACCESS_STATE, GRANTED)
            .set(CHANNELS.SUBSCRIPTION_STATE, INACTIVE)
            .set(CHANNELS.SUBSCRIPTION_ATTEMPTS, 2)
            .set(CHANNELS.SUBSCRIPTION_RETRY_AT, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
            .execute();
    }

    private long insertExpiredAccess(UUID processingToken) {
        return dslContext.insertInto(CHANNELS)
            .set(CHANNELS.SOURCE_TYPE, TELEGRAM)
            .set(CHANNELS.SOURCE_URI, "https://t.me/invite")
            .set(CHANNELS.ACCESS_STATE, UNKNOWN)
            .set(CHANNELS.ACCESS_ATTEMPTS, 2)
            .set(CHANNELS.ACCESS_RETRY_AT, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2))
            .set(CHANNELS.SUBSCRIPTION_STATE, ACTIVE)
            .set(CHANNELS.PROCESSING_OPERATION, ACCESS)
            .set(CHANNELS.PROCESSING_TOKEN, processingToken)
            .set(CHANNELS.PROCESSING_UNTIL, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
            .returningResult(CHANNELS.ID)
            .fetchSingle(CHANNELS.ID);
    }

}
