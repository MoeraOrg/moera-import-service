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
import org.moera.importer.application.FailureSeries;
import org.jooq.Field;
import org.jooq.TableField;
import org.moera.importer.model.ChannelOperation;
import org.moera.importer.model.SourceType;
import org.moera.importer.observability.ImportMetrics;
import org.moera.importer.persistence.jooq.tables.records.ChannelsRecord;
import org.moera.importer.source.AccessResult;
import org.moera.importer.source.ChannelResolution;
import org.moera.importer.source.ChannelMetadata;
import org.moera.importer.source.ChannelSnapshot;
import org.moera.importer.source.InitialImportMode;
import org.moera.importer.source.SubscriptionResult;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.currentOffsetDateTime;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.trueCondition;
import static org.jooq.impl.DSL.val;
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

    public boolean updateMetadata(SourceType sourceType, String sourceId, ChannelMetadata metadata) {
        var generatedSourceType = org.moera.importer.persistence.jooq.enums.SourceType.valueOf(sourceType.name());
        return dslContext.update(CHANNELS)
            .set(CHANNELS.TITLE, metadata.title())
            .set(CHANNELS.DESCRIPTION_HTML, metadata.descriptionHtml())
            .set(CHANNELS.PUBLIC_USERNAME, metadata.publicUsername())
            .set(CHANNELS.EMBLEM_REMOTE_ID, emblemRemoteId(metadata))
            .set(CHANNELS.EMBLEM_UNIQUE_ID, emblemUniqueId(metadata))
            .set(CHANNELS.EMBLEM_MIME_TYPE, emblemMimeType(metadata))
            .set(CHANNELS.UPDATED_AT, currentOffsetDateTime())
            .where(CHANNELS.SOURCE_TYPE.eq(generatedSourceType))
            .and(CHANNELS.SOURCE_ID.eq(sourceId))
            .execute() == 1;
    }

    public ChannelSnapshot create(
        SourceType sourceType,
        InitialImportMode initialImportMode,
        Instant initialImportAfter,
        ChannelResolution resolution
    ) {
        initialImportMode.validate(initialImportAfter);
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
            .set(CHANNELS.INITIAL_IMPORT_MODE,
                org.moera.importer.persistence.jooq.enums.InitialImportMode.valueOf(initialImportMode.name()))
            .set(CHANNELS.INITIAL_IMPORT_AFTER, jooqMappings.offsetDateTime(initialImportAfter))
            .set(CHANNELS.TITLE, resolution.metadata() != null ? resolution.metadata().title() : null)
            .set(CHANNELS.DESCRIPTION_HTML,
                resolution.metadata() != null ? resolution.metadata().descriptionHtml() : null)
            .set(CHANNELS.PUBLIC_USERNAME,
                resolution.metadata() != null ? resolution.metadata().publicUsername() : null)
            .set(CHANNELS.EMBLEM_REMOTE_ID, emblemRemoteId(resolution.metadata()))
            .set(CHANNELS.EMBLEM_UNIQUE_ID, emblemUniqueId(resolution.metadata()))
            .set(CHANNELS.EMBLEM_MIME_TYPE, emblemMimeType(resolution.metadata()))
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
            CHANNELS.ACCESS_FAILURE_TYPE,
            CHANNELS.ACCESS_FAILURE_STARTED_AT,
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
            CHANNELS.SUBSCRIPTION_FAILURE_TYPE,
            CHANNELS.SUBSCRIPTION_FAILURE_STARTED_AT,
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
            CHANNELS.POLL_FAILURE_TYPE,
            CHANNELS.POLL_FAILURE_STARTED_AT,
            CHANNELS.NEXT_POLL_AT.isNotNull().and(
                CHANNELS.ACCESS_STATE.eq(org.moera.importer.persistence.jooq.enums.AccessState.GRANTED)
            )
        );
    }

    public boolean renewPollLease(long channelId, UUID token, Duration lease) {
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        int updated = dslContext.update(CHANNELS)
            .set(CHANNELS.PROCESSING_UNTIL, field(
                "current_timestamp + ({0} * interval '1 millisecond')",
                OffsetDateTime.class,
                val(lease.toMillis())
            ))
            .set(CHANNELS.UPDATED_AT, currentOffsetDateTime())
            .where(CHANNELS.ID.eq(channelId))
            .and(CHANNELS.PROCESSING_OPERATION.eq(
                org.moera.importer.persistence.jooq.enums.ChannelOperation.POLL
            ))
            .and(CHANNELS.PROCESSING_TOKEN.eq(token))
            .and(CHANNELS.PROCESSING_UNTIL.gt(currentOffsetDateTime()))
            .execute();
        return updated == 1;
    }

    public boolean completeAccess(long channelId, UUID token, AccessResult result) {
        org.moera.importer.persistence.jooq.enums.AccessState state =
            org.moera.importer.persistence.jooq.enums.AccessState.valueOf(result.state().name());
        var sourceIdentityMatches = result.resolvedSourceId() == null
            ? trueCondition()
            : CHANNELS.SOURCE_ID.isNull().or(CHANNELS.SOURCE_ID.eq(result.resolvedSourceId()));
        int updated = dslContext.update(CHANNELS)
            .set(CHANNELS.SOURCE_ID, coalesce(CHANNELS.SOURCE_ID, result.resolvedSourceId()))
            .set(CHANNELS.TITLE, metadataTitle(result.metadata()))
            .set(CHANNELS.DESCRIPTION_HTML, metadataDescriptionHtml(result.metadata()))
            .set(CHANNELS.PUBLIC_USERNAME, metadataPublicUsername(result.metadata()))
            .set(CHANNELS.EMBLEM_REMOTE_ID, metadataEmblemRemoteId(result.metadata()))
            .set(CHANNELS.EMBLEM_UNIQUE_ID, metadataEmblemUniqueId(result.metadata()))
            .set(CHANNELS.EMBLEM_MIME_TYPE, metadataEmblemMimeType(result.metadata()))
            .set(CHANNELS.ACCESS_STATE, state)
            .set(CHANNELS.ACCESS_ATTEMPTS, 0)
            .setNull(CHANNELS.ACCESS_RETRY_AT)
            .setNull(CHANNELS.ACCESS_LAST_ERROR)
            .setNull(CHANNELS.ACCESS_FAILURE_TYPE)
            .setNull(CHANNELS.ACCESS_FAILURE_STARTED_AT)
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
            .setNull(CHANNELS.SUBSCRIPTION_FAILURE_TYPE)
            .setNull(CHANNELS.SUBSCRIPTION_FAILURE_STARTED_AT)
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

    public boolean failAccess(
        long channelId,
        UUID token,
        Instant nextAttemptAt,
        String error,
        FailureSeries failureSeries
    ) {
        return fail(
            channelId,
            token,
            org.moera.importer.persistence.jooq.enums.ChannelOperation.ACCESS,
            CHANNELS.ACCESS_RETRY_AT,
            CHANNELS.ACCESS_LAST_ERROR,
            CHANNELS.ACCESS_FAILURE_TYPE,
            CHANNELS.ACCESS_FAILURE_STARTED_AT,
            nextAttemptAt,
            error,
            failureSeries
        );
    }

    public boolean failSubscription(
        long channelId,
        UUID token,
        Instant nextAttemptAt,
        String error,
        FailureSeries failureSeries
    ) {
        return fail(
            channelId,
            token,
            org.moera.importer.persistence.jooq.enums.ChannelOperation.SUBSCRIPTION,
            CHANNELS.SUBSCRIPTION_RETRY_AT,
            CHANNELS.SUBSCRIPTION_LAST_ERROR,
            CHANNELS.SUBSCRIPTION_FAILURE_TYPE,
            CHANNELS.SUBSCRIPTION_FAILURE_STARTED_AT,
            nextAttemptAt,
            error,
            failureSeries
        );
    }

    public boolean failPoll(
        long channelId,
        UUID token,
        Instant nextAttemptAt,
        String error,
        FailureSeries failureSeries
    ) {
        return fail(
            channelId,
            token,
            org.moera.importer.persistence.jooq.enums.ChannelOperation.POLL,
            CHANNELS.NEXT_POLL_AT,
            CHANNELS.POLL_LAST_ERROR,
            CHANNELS.POLL_FAILURE_TYPE,
            CHANNELS.POLL_FAILURE_STARTED_AT,
            nextAttemptAt,
            error,
            failureSeries
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
            .setNull(CHANNELS.ACCESS_FAILURE_TYPE)
            .setNull(CHANNELS.ACCESS_FAILURE_STARTED_AT)
            .set(CHANNELS.ACCESS_DATA, jooqMappings.json(data))
            .set(CHANNELS.SUBSCRIPTION_STATE,
                org.moera.importer.persistence.jooq.enums.SubscriptionState.LOST)
            .set(CHANNELS.SUBSCRIPTION_ATTEMPTS, 0)
            .set(CHANNELS.SUBSCRIPTION_RETRY_AT, now)
            .setNull(CHANNELS.SUBSCRIPTION_LAST_ERROR)
            .setNull(CHANNELS.SUBSCRIPTION_FAILURE_TYPE)
            .setNull(CHANNELS.SUBSCRIPTION_FAILURE_STARTED_AT)
            .set(CHANNELS.POLL_ATTEMPTS, 0)
            .set(CHANNELS.NEXT_POLL_AT, now)
            .setNull(CHANNELS.POLL_LAST_ERROR)
            .setNull(CHANNELS.POLL_FAILURE_TYPE)
            .setNull(CHANNELS.POLL_FAILURE_STARTED_AT)
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
            .setNull(CHANNELS.SUBSCRIPTION_FAILURE_TYPE)
            .setNull(CHANNELS.SUBSCRIPTION_FAILURE_STARTED_AT)
            .set(CHANNELS.SUBSCRIPTION_DATA, jooqMappings.json(data))
            .set(CHANNELS.POLL_ATTEMPTS, 0)
            .set(CHANNELS.NEXT_POLL_AT, now)
            .setNull(CHANNELS.POLL_LAST_ERROR)
            .setNull(CHANNELS.POLL_FAILURE_TYPE)
            .setNull(CHANNELS.POLL_FAILURE_STARTED_AT)
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
        TableField<ChannelsRecord, String> failureType,
        TableField<ChannelsRecord, OffsetDateTime> failureStartedAt,
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
                claimedAttempts,
                failureSeries(record.get(failureType), record.get(failureStartedAt))
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
        TableField<ChannelsRecord, String> failureTypeField,
        TableField<ChannelsRecord, OffsetDateTime> failureStartedAtField,
        Instant nextAttemptAt,
        String error,
        FailureSeries failureSeries
    ) {
        int updated = dslContext.update(CHANNELS)
            .set(nextAttemptAtField, jooqMappings.offsetDateTime(nextAttemptAt))
            .set(errorField, error)
            .set(failureTypeField, failureSeries != null ? failureSeries.type() : null)
            .set(
                failureStartedAtField,
                failureSeries != null ? jooqMappings.offsetDateTime(failureSeries.startedAt()) : null
            )
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

    private FailureSeries failureSeries(String type, OffsetDateTime startedAt) {
        return type != null && startedAt != null ? new FailureSeries(type, startedAt.toInstant()) : null;
    }

    private Field<String> metadataTitle(ChannelMetadata metadata) {
        return metadata != null ? val(metadata.title()) : CHANNELS.TITLE;
    }

    private Field<String> metadataDescriptionHtml(ChannelMetadata metadata) {
        return metadata != null ? val(metadata.descriptionHtml()) : CHANNELS.DESCRIPTION_HTML;
    }

    private Field<String> metadataPublicUsername(ChannelMetadata metadata) {
        return metadata != null ? val(metadata.publicUsername(), String.class) : CHANNELS.PUBLIC_USERNAME;
    }

    private Field<String> metadataEmblemRemoteId(ChannelMetadata metadata) {
        return metadata != null ? val(emblemRemoteId(metadata), String.class) : CHANNELS.EMBLEM_REMOTE_ID;
    }

    private Field<String> metadataEmblemUniqueId(ChannelMetadata metadata) {
        return metadata != null ? val(emblemUniqueId(metadata), String.class) : CHANNELS.EMBLEM_UNIQUE_ID;
    }

    private Field<String> metadataEmblemMimeType(ChannelMetadata metadata) {
        return metadata != null ? val(emblemMimeType(metadata), String.class) : CHANNELS.EMBLEM_MIME_TYPE;
    }

    private String emblemRemoteId(ChannelMetadata metadata) {
        return metadata != null && metadata.emblem() != null ? metadata.emblem().remoteId() : null;
    }

    private String emblemUniqueId(ChannelMetadata metadata) {
        return metadata != null && metadata.emblem() != null ? metadata.emblem().uniqueId() : null;
    }

    private String emblemMimeType(ChannelMetadata metadata) {
        return metadata != null && metadata.emblem() != null ? metadata.emblem().mimeType() : null;
    }

}
