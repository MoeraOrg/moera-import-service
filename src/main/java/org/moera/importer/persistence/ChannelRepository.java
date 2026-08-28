package org.moera.importer.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.TableField;
import org.moera.importer.model.ChannelOperation;
import org.moera.importer.model.SourceType;
import org.moera.importer.observability.ImportMetrics;
import org.moera.importer.persistence.jooq.tables.records.ChannelsRecord;
import org.moera.importer.source.AccessResult;
import org.moera.importer.source.ChannelResolution;
import org.moera.importer.source.ChannelSnapshot;
import org.moera.importer.source.SubscriptionResult;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.trueCondition;
import static org.moera.importer.persistence.jooq.tables.Channels.CHANNELS;

@Repository
public class ChannelRepository {

    @Inject
    private Clock clock;

    @Inject
    private DSLContext dslContext;

    @Inject
    private JooqMappings jooqMappings;

    @Inject
    private ImportMetrics importMetrics;

    public Optional<ChannelSnapshot> find(long id) {
        return dslContext.selectFrom(CHANNELS)
            .where(CHANNELS.ID.eq(id))
            .fetchOptional()
            .map(jooqMappings::channelSnapshot);
    }

    public Optional<ChannelSnapshot> find(SourceType sourceType, String sourceId) {
        var generatedSourceType = org.moera.importer.persistence.jooq.enums.SourceType.valueOf(sourceType.name());
        return dslContext.selectFrom(CHANNELS)
            .where(CHANNELS.SOURCE_TYPE.eq(generatedSourceType))
            .and(CHANNELS.SOURCE_ID.eq(sourceId))
            .fetchOptional()
            .map(jooqMappings::channelSnapshot);
    }

    public List<ChannelSnapshot> findAll() {
        return dslContext.selectFrom(CHANNELS)
            .orderBy(CHANNELS.ID)
            .fetch(jooqMappings::channelSnapshot);
    }

    public ChannelSnapshot create(SourceType sourceType, ChannelResolution resolution) {
        OffsetDateTime now = jooqMappings.offsetDateTime(clock.instant());
        OffsetDateTime accessRetryAt = resolution.accessState() == org.moera.importer.model.AccessState.GRANTED
            ? null : now;
        OffsetDateTime subscriptionRetryAt = switch (resolution.subscriptionState()) {
            case ACTIVE, UNSUPPORTED -> null;
            default -> now;
        };
        ChannelsRecord record = dslContext.insertInto(CHANNELS)
            .set(CHANNELS.SOURCE_TYPE,
                org.moera.importer.persistence.jooq.enums.SourceType.valueOf(sourceType.name()))
            .set(CHANNELS.SOURCE_ID, resolution.sourceId())
            .set(CHANNELS.SOURCE_URI, resolution.sourceUri().toString())
            .set(CHANNELS.SOURCE_DATA, jooqMappings.json(resolution.sourceData()))
            .set(CHANNELS.ACCESS_STATE,
                org.moera.importer.persistence.jooq.enums.AccessState.valueOf(resolution.accessState().name()))
            .set(CHANNELS.ACCESS_RETRY_AT, accessRetryAt)
            .set(CHANNELS.ACCESS_DATA, jooqMappings.json(resolution.accessData()))
            .set(CHANNELS.SUBSCRIPTION_STATE, org.moera.importer.persistence.jooq.enums.SubscriptionState.valueOf(
                resolution.subscriptionState().name()
            ))
            .set(CHANNELS.SUBSCRIPTION_RETRY_AT, subscriptionRetryAt)
            .set(CHANNELS.SUBSCRIPTION_DATA, jooqMappings.json(resolution.subscriptionData()))
            .set(CHANNELS.NEXT_POLL_AT, now)
            .returning()
            .fetchSingle();
        return jooqMappings.channelSnapshot(record);
    }

    public boolean delete(long id) {
        return dslContext.deleteFrom(CHANNELS)
            .where(CHANNELS.ID.eq(id))
            .execute() == 1;
    }

    @Transactional
    public List<ChannelClaim> claimAccess(int limit, Duration lease) {
        return claim(
            limit,
            lease,
            ChannelOperation.ACCESS,
            org.moera.importer.persistence.jooq.enums.ChannelOperation.ACCESS,
            CHANNELS.ACCESS_RETRY_AT,
            CHANNELS.ACCESS_ATTEMPTS,
            CHANNELS.ACCESS_RETRY_AT.isNotNull()
        );
    }

    @Transactional
    public List<ChannelClaim> claimSubscriptions(int limit, Duration lease) {
        return claim(
            limit,
            lease,
            ChannelOperation.SUBSCRIPTION,
            org.moera.importer.persistence.jooq.enums.ChannelOperation.SUBSCRIPTION,
            CHANNELS.SUBSCRIPTION_RETRY_AT,
            CHANNELS.SUBSCRIPTION_ATTEMPTS,
            CHANNELS.SUBSCRIPTION_RETRY_AT.isNotNull().and(
                CHANNELS.ACCESS_STATE.eq(org.moera.importer.persistence.jooq.enums.AccessState.GRANTED)
            )
        );
    }

    @Transactional
    public List<ChannelClaim> claimPolls(int limit, Duration lease) {
        return claim(
            limit,
            lease,
            ChannelOperation.POLL,
            org.moera.importer.persistence.jooq.enums.ChannelOperation.POLL,
            CHANNELS.NEXT_POLL_AT,
            CHANNELS.POLL_ATTEMPTS,
            CHANNELS.NEXT_POLL_AT.isNotNull().and(
                CHANNELS.ACCESS_STATE.eq(org.moera.importer.persistence.jooq.enums.AccessState.GRANTED)
            )
        );
    }

    public boolean completeAccess(long channelId, UUID token, AccessResult result) {
        org.moera.importer.persistence.jooq.enums.AccessState state =
            org.moera.importer.persistence.jooq.enums.AccessState.valueOf(result.state().name());
        var sourceIdentityMatches = result.resolvedSourceId() == null
            ? trueCondition()
            : CHANNELS.SOURCE_ID.isNull().or(CHANNELS.SOURCE_ID.eq(result.resolvedSourceId()));
        int updated = dslContext.update(CHANNELS)
            .set(CHANNELS.SOURCE_ID, coalesce(CHANNELS.SOURCE_ID, result.resolvedSourceId()))
            .set(CHANNELS.ACCESS_STATE, state)
            .set(CHANNELS.ACCESS_ATTEMPTS, 0)
            .setNull(CHANNELS.ACCESS_RETRY_AT)
            .setNull(CHANNELS.ACCESS_LAST_ERROR)
            .set(CHANNELS.ACCESS_DATA, jooqMappings.json(result.data()))
            .setNull(CHANNELS.PROCESSING_OPERATION)
            .setNull(CHANNELS.PROCESSING_TOKEN)
            .setNull(CHANNELS.PROCESSING_UNTIL)
            .set(CHANNELS.UPDATED_AT, jooqMappings.offsetDateTime(clock.instant()))
            .where(CHANNELS.ID.eq(channelId))
            .and(CHANNELS.PROCESSING_OPERATION.eq(
                org.moera.importer.persistence.jooq.enums.ChannelOperation.ACCESS
            ))
            .and(CHANNELS.PROCESSING_TOKEN.eq(token))
            .and(sourceIdentityMatches)
            .execute();
        return updated == 1;
    }

    public boolean completeSubscription(long channelId, UUID token, SubscriptionResult result) {
        org.moera.importer.persistence.jooq.enums.SubscriptionState state =
            org.moera.importer.persistence.jooq.enums.SubscriptionState.valueOf(result.state().name());
        int updated = dslContext.update(CHANNELS)
            .set(CHANNELS.SUBSCRIPTION_STATE, state)
            .set(CHANNELS.SUBSCRIPTION_ATTEMPTS, 0)
            .setNull(CHANNELS.SUBSCRIPTION_RETRY_AT)
            .setNull(CHANNELS.SUBSCRIPTION_LAST_ERROR)
            .set(CHANNELS.SUBSCRIPTION_DATA, jooqMappings.json(result.data()))
            .setNull(CHANNELS.PROCESSING_OPERATION)
            .setNull(CHANNELS.PROCESSING_TOKEN)
            .setNull(CHANNELS.PROCESSING_UNTIL)
            .set(CHANNELS.UPDATED_AT, jooqMappings.offsetDateTime(clock.instant()))
            .where(CHANNELS.ID.eq(channelId))
            .and(CHANNELS.PROCESSING_OPERATION.eq(
                org.moera.importer.persistence.jooq.enums.ChannelOperation.SUBSCRIPTION
            ))
            .and(CHANNELS.PROCESSING_TOKEN.eq(token))
            .execute();
        return updated == 1;
    }

    public boolean failAccess(long channelId, UUID token, Instant nextAttemptAt, String error) {
        return fail(
            channelId,
            token,
            org.moera.importer.persistence.jooq.enums.ChannelOperation.ACCESS,
            CHANNELS.ACCESS_RETRY_AT,
            CHANNELS.ACCESS_LAST_ERROR,
            nextAttemptAt,
            error
        );
    }

    public boolean failSubscription(long channelId, UUID token, Instant nextAttemptAt, String error) {
        return fail(
            channelId,
            token,
            org.moera.importer.persistence.jooq.enums.ChannelOperation.SUBSCRIPTION,
            CHANNELS.SUBSCRIPTION_RETRY_AT,
            CHANNELS.SUBSCRIPTION_LAST_ERROR,
            nextAttemptAt,
            error
        );
    }

    public boolean failPoll(long channelId, UUID token, Instant nextAttemptAt, String error) {
        return fail(
            channelId,
            token,
            org.moera.importer.persistence.jooq.enums.ChannelOperation.POLL,
            CHANNELS.NEXT_POLL_AT,
            CHANNELS.POLL_LAST_ERROR,
            nextAttemptAt,
            error
        );
    }

    public boolean markAccessLost(SourceType sourceType, String sourceId, JsonNode data) {
        var generatedSourceType = org.moera.importer.persistence.jooq.enums.SourceType.valueOf(sourceType.name());
        OffsetDateTime now = jooqMappings.offsetDateTime(clock.instant());
        int updated = dslContext.update(CHANNELS)
            .set(CHANNELS.ACCESS_STATE, org.moera.importer.persistence.jooq.enums.AccessState.DENIED)
            .set(CHANNELS.ACCESS_ATTEMPTS, 0)
            .set(CHANNELS.ACCESS_RETRY_AT, now)
            .setNull(CHANNELS.ACCESS_LAST_ERROR)
            .set(CHANNELS.ACCESS_DATA, jooqMappings.json(data))
            .set(CHANNELS.SUBSCRIPTION_STATE,
                org.moera.importer.persistence.jooq.enums.SubscriptionState.LOST)
            .set(CHANNELS.SUBSCRIPTION_ATTEMPTS, 0)
            .set(CHANNELS.SUBSCRIPTION_RETRY_AT, now)
            .setNull(CHANNELS.SUBSCRIPTION_LAST_ERROR)
            .set(CHANNELS.POLL_ATTEMPTS, 0)
            .set(CHANNELS.NEXT_POLL_AT, now)
            .setNull(CHANNELS.POLL_LAST_ERROR)
            .setNull(CHANNELS.PROCESSING_OPERATION)
            .setNull(CHANNELS.PROCESSING_TOKEN)
            .setNull(CHANNELS.PROCESSING_UNTIL)
            .set(CHANNELS.UPDATED_AT, now)
            .where(CHANNELS.SOURCE_TYPE.eq(generatedSourceType))
            .and(CHANNELS.SOURCE_ID.eq(sourceId))
            .execute();
        return updated == 1;
    }

    public boolean markSubscriptionLost(SourceType sourceType, String sourceId, JsonNode data) {
        var generatedSourceType = org.moera.importer.persistence.jooq.enums.SourceType.valueOf(sourceType.name());
        OffsetDateTime now = jooqMappings.offsetDateTime(clock.instant());
        int updated = dslContext.update(CHANNELS)
            .set(CHANNELS.SUBSCRIPTION_STATE,
                org.moera.importer.persistence.jooq.enums.SubscriptionState.LOST)
            .set(CHANNELS.SUBSCRIPTION_ATTEMPTS, 0)
            .set(CHANNELS.SUBSCRIPTION_RETRY_AT, now)
            .setNull(CHANNELS.SUBSCRIPTION_LAST_ERROR)
            .set(CHANNELS.SUBSCRIPTION_DATA, jooqMappings.json(data))
            .set(CHANNELS.POLL_ATTEMPTS, 0)
            .set(CHANNELS.NEXT_POLL_AT, now)
            .setNull(CHANNELS.POLL_LAST_ERROR)
            .setNull(CHANNELS.PROCESSING_OPERATION)
            .setNull(CHANNELS.PROCESSING_TOKEN)
            .setNull(CHANNELS.PROCESSING_UNTIL)
            .set(CHANNELS.UPDATED_AT, now)
            .where(CHANNELS.SOURCE_TYPE.eq(generatedSourceType))
            .and(CHANNELS.SOURCE_ID.eq(sourceId))
            .execute();
        return updated == 1;
    }

    private List<ChannelClaim> claim(
        int limit,
        Duration lease,
        ChannelOperation operation,
        org.moera.importer.persistence.jooq.enums.ChannelOperation generatedOperation,
        TableField<ChannelsRecord, OffsetDateTime> dueAt,
        TableField<ChannelsRecord, Integer> attempts,
        Condition valid
    ) {
        if (limit <= 0) {
            return List.of();
        }
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }

        OffsetDateTime now = jooqMappings.offsetDateTime(clock.instant());
        List<ChannelsRecord> records = dslContext.selectFrom(CHANNELS)
            .where(valid)
            .and(dueAt.le(now))
            .and(CHANNELS.PROCESSING_UNTIL.isNull().or(CHANNELS.PROCESSING_UNTIL.lt(now)))
            .orderBy(dueAt, CHANNELS.ID)
            .limit(limit)
            .forUpdate()
            .skipLocked()
            .fetch();

        importMetrics.leaseReclaimed(
            "channel",
            (int) records.stream().filter(record -> record.getProcessingUntil() != null).count()
        );

        List<ChannelClaim> claims = new ArrayList<>(records.size());
        for (ChannelsRecord record : records) {
            UUID processingToken = UUID.randomUUID();
            int claimedAttempts = record.get(attempts) + 1;
            dslContext.update(CHANNELS)
                .set(CHANNELS.PROCESSING_OPERATION, generatedOperation)
                .set(CHANNELS.PROCESSING_TOKEN, processingToken)
                .set(CHANNELS.PROCESSING_UNTIL, now.plus(lease))
                .set(attempts, claimedAttempts)
                .set(CHANNELS.UPDATED_AT, now)
                .where(CHANNELS.ID.eq(record.getId()))
                .execute();
            claims.add(new ChannelClaim(
                jooqMappings.channelSnapshot(record),
                operation,
                processingToken,
                claimedAttempts
            ));
        }
        return List.copyOf(claims);
    }

    private boolean fail(
        long channelId,
        UUID token,
        org.moera.importer.persistence.jooq.enums.ChannelOperation operation,
        TableField<ChannelsRecord, OffsetDateTime> nextAttemptAtField,
        TableField<ChannelsRecord, String> errorField,
        Instant nextAttemptAt,
        String error
    ) {
        int updated = dslContext.update(CHANNELS)
            .set(nextAttemptAtField, jooqMappings.offsetDateTime(nextAttemptAt))
            .set(errorField, error)
            .setNull(CHANNELS.PROCESSING_OPERATION)
            .setNull(CHANNELS.PROCESSING_TOKEN)
            .setNull(CHANNELS.PROCESSING_UNTIL)
            .set(CHANNELS.UPDATED_AT, jooqMappings.offsetDateTime(clock.instant()))
            .where(CHANNELS.ID.eq(channelId))
            .and(CHANNELS.PROCESSING_OPERATION.eq(operation))
            .and(CHANNELS.PROCESSING_TOKEN.eq(token))
            .execute();
        return updated == 1;
    }

}
