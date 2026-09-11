package org.moera.importer.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import jakarta.inject.Inject;

import org.jooq.DSLContext;
import org.moera.importer.application.FailureSeries;
import org.moera.importer.model.MediaSkipReason;
import org.moera.importer.observability.ImportMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.moera.importer.persistence.jooq.enums.PublicationMediaStatus.PENDING;
import static org.moera.importer.persistence.jooq.enums.PublicationMediaStatus.SKIPPED;
import static org.moera.importer.persistence.jooq.enums.PublicationMediaStatus.UPLOADED;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.selectOne;
import static org.moera.importer.persistence.jooq.tables.Channels.CHANNELS;
import static org.moera.importer.persistence.jooq.tables.Destinations.DESTINATIONS;
import static org.moera.importer.persistence.jooq.tables.Media.MEDIA;
import static org.moera.importer.persistence.jooq.tables.MessageLinkPreviews.MESSAGE_LINK_PREVIEWS;
import static org.moera.importer.persistence.jooq.tables.Messages.MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Mirrors.MIRRORS;
import static org.moera.importer.persistence.jooq.tables.PublicationMedia.PUBLICATION_MEDIA;
import static org.moera.importer.persistence.jooq.tables.PublicationMessages.PUBLICATION_MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Publications.PUBLICATIONS;

@Repository
public class PublicationMediaRepository {

    private static final Logger log = LoggerFactory.getLogger(PublicationMediaRepository.class);

    @Inject
    private Clock clock;

    @Inject
    private DSLContext dslContext;

    @Inject
    private JooqMappings jooqMappings;

    @Inject
    private ImportMetrics importMetrics;

    @Transactional
    public List<PublicationMediaClaim> claimPending(int limit, Duration lease) {
        Instant now = clock.instant();
        var records = dslContext.select(
                PUBLICATION_MEDIA.PUBLICATION_ID,
                PUBLICATION_MEDIA.MEDIA_ID,
                PUBLICATION_MEDIA.ATTEMPTS,
                PUBLICATION_MEDIA.FAILURE_TYPE,
                PUBLICATION_MEDIA.FAILURE_STARTED_AT,
                PUBLICATION_MEDIA.PROCESSING_TOKEN,
                PUBLICATION_MEDIA.PROCESSING_UNTIL
            )
            .from(PUBLICATION_MEDIA)
            .where(PUBLICATION_MEDIA.STATUS.eq(PENDING))
            .and(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT.le(jooqMappings.offsetDateTime(now)))
            .and(PUBLICATION_MEDIA.PROCESSING_UNTIL.isNull()
                .or(PUBLICATION_MEDIA.PROCESSING_UNTIL.le(jooqMappings.offsetDateTime(now))))
            .orderBy(
                PUBLICATION_MEDIA.NEXT_ATTEMPT_AT,
                PUBLICATION_MEDIA.PUBLICATION_ID,
                PUBLICATION_MEDIA.MEDIA_ID
            )
            .limit(limit)
            .forUpdate()
            .skipLocked()
            .fetch();
        importMetrics.leaseReclaimed(
            "publication_media",
            (int) records.stream().filter(record -> record.get(PUBLICATION_MEDIA.PROCESSING_UNTIL) != null).count()
        );
        return records.stream().map(record -> {
            long publicationId = record.get(PUBLICATION_MEDIA.PUBLICATION_ID);
            long mediaId = record.get(PUBLICATION_MEDIA.MEDIA_ID);
            int attempts = record.get(PUBLICATION_MEDIA.ATTEMPTS) + 1;
            UUID processingToken = UUID.randomUUID();
            UUID previousProcessingToken = record.get(PUBLICATION_MEDIA.PROCESSING_TOKEN);
            var previousProcessingUntil = record.get(PUBLICATION_MEDIA.PROCESSING_UNTIL);
            dslContext.update(PUBLICATION_MEDIA)
                .set(PUBLICATION_MEDIA.ATTEMPTS, attempts)
                .set(PUBLICATION_MEDIA.PROCESSING_TOKEN, processingToken)
                .set(PUBLICATION_MEDIA.PROCESSING_UNTIL, jooqMappings.offsetDateTime(now.plus(lease)))
                .set(PUBLICATION_MEDIA.UPDATED_AT, jooqMappings.offsetDateTime(now))
                .where(PUBLICATION_MEDIA.PUBLICATION_ID.eq(publicationId))
                .and(PUBLICATION_MEDIA.MEDIA_ID.eq(mediaId))
                .execute();
            if (previousProcessingUntil != null) {
                logReclaimAfterCommit(
                    publicationId,
                    mediaId,
                    previousProcessingToken,
                    previousProcessingUntil,
                    processingToken,
                    attempts
                );
            }
            return new PublicationMediaClaim(
                publicationId,
                mediaId,
                processingToken,
                attempts,
                failureSeries(
                    record.get(PUBLICATION_MEDIA.FAILURE_TYPE),
                    record.get(PUBLICATION_MEDIA.FAILURE_STARTED_AT)
                )
            );
        }).toList();
    }

    private void logReclaimAfterCommit(
        long publicationId,
        long mediaId,
        UUID previousProcessingToken,
        java.time.OffsetDateTime previousProcessingUntil,
        UUID processingToken,
        int attempts
    ) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

            @Override
            public void afterCommit() {
                log.warn(
                    "Publication media lease reclaimed: operation=publication_media, publication_id={}, media_id={}, "
                        + "previous_processing_token={}, previous_processing_until={}, processing_token={}, attempt={}",
                    publicationId, mediaId, previousProcessingToken, previousProcessingUntil, processingToken, attempts
                );
            }

        });
    }

    public PublicationMediaContext loadContext(long publicationId, long mediaId) {
        var record = dslContext.select(
                PUBLICATION_MEDIA.DESTINATION_MEDIA_ID,
                PUBLICATION_MEDIA.DESTINATION_HASH,
                PUBLICATION_MEDIA.COMPRESSION_PENDING
            )
            .select(DESTINATIONS.fields())
            .select(CHANNELS.fields())
            .select(MEDIA.fields())
            .from(PUBLICATION_MEDIA)
            .join(PUBLICATIONS).on(PUBLICATIONS.ID.eq(PUBLICATION_MEDIA.PUBLICATION_ID))
            .join(MIRRORS).on(MIRRORS.ID.eq(PUBLICATIONS.MIRROR_ID))
            .join(DESTINATIONS).on(DESTINATIONS.ID.eq(MIRRORS.DESTINATION_ID))
            .join(MEDIA).on(MEDIA.ID.eq(PUBLICATION_MEDIA.MEDIA_ID))
            .join(MESSAGES).on(MESSAGES.ID.eq(MEDIA.MESSAGE_ID))
            .join(CHANNELS).on(CHANNELS.ID.eq(MESSAGES.CHANNEL_ID))
            .where(PUBLICATION_MEDIA.PUBLICATION_ID.eq(publicationId))
            .and(PUBLICATION_MEDIA.MEDIA_ID.eq(mediaId))
            .fetchSingle();
        return new PublicationMediaContext(
            publicationId,
            jooqMappings.destination(record.into(DESTINATIONS)),
            jooqMappings.channelSnapshot(record.into(CHANNELS)),
            jooqMappings.media(record.into(MEDIA)),
            record.get(PUBLICATION_MEDIA.DESTINATION_MEDIA_ID),
            record.get(PUBLICATION_MEDIA.DESTINATION_HASH),
            record.get(PUBLICATION_MEDIA.COMPRESSION_PENDING)
        );
    }

    public Set<StagingMediaKey> findUploadPending(List<StagingMediaKey> keys) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        org.jooq.Condition candidates = org.jooq.impl.DSL.falseCondition();
        for (StagingMediaKey key : keys) {
            candidates = candidates.or(
                PUBLICATION_MEDIA.PUBLICATION_ID.eq(key.publicationId())
                    .and(PUBLICATION_MEDIA.MEDIA_ID.eq(key.mediaId()))
            );
        }
        return dslContext.select(
                PUBLICATION_MEDIA.PUBLICATION_ID,
                PUBLICATION_MEDIA.MEDIA_ID,
                MEDIA.SOURCE_HASH
            )
            .from(PUBLICATION_MEDIA)
            .join(MEDIA).on(MEDIA.ID.eq(PUBLICATION_MEDIA.MEDIA_ID))
            .where(candidates)
            .and(PUBLICATION_MEDIA.STATUS.eq(PENDING))
            .and(PUBLICATION_MEDIA.DESTINATION_MEDIA_ID.isNull())
            .fetch(record -> new StagingMediaKey(
                record.get(PUBLICATION_MEDIA.PUBLICATION_ID),
                record.get(PUBLICATION_MEDIA.MEDIA_ID),
                HexFormat.of().formatHex(record.get(MEDIA.SOURCE_HASH))
            ))
            .stream()
            .filter(keys::contains)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean awaitingCompression(
        long publicationId,
        long mediaId,
        UUID token,
        byte[] expectedSourceHash,
        String originalMediaId,
        String destinationHash,
        Instant nextAttemptAt
    ) {
        Instant now = clock.instant();
        return dslContext.update(PUBLICATION_MEDIA)
            .set(PUBLICATION_MEDIA.STATUS, PENDING)
            .setNull(PUBLICATION_MEDIA.SKIP_REASON)
            .set(PUBLICATION_MEDIA.DESTINATION_MEDIA_ID, originalMediaId)
            .set(PUBLICATION_MEDIA.DESTINATION_HASH, destinationHash)
            .set(PUBLICATION_MEDIA.COMPRESSION_PENDING, true)
            .set(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT, jooqMappings.offsetDateTime(nextAttemptAt))
            .setNull(PUBLICATION_MEDIA.LAST_ERROR)
            .setNull(PUBLICATION_MEDIA.FAILURE_TYPE)
            .setNull(PUBLICATION_MEDIA.FAILURE_STARTED_AT)
            .setNull(PUBLICATION_MEDIA.PROCESSING_TOKEN)
            .setNull(PUBLICATION_MEDIA.PROCESSING_UNTIL)
            .set(PUBLICATION_MEDIA.UPDATED_AT, jooqMappings.offsetDateTime(now))
            .where(claimMatches(publicationId, mediaId, token))
            .and(sourceHashMatches(mediaId, expectedSourceHash))
            .execute() == 1;
    }

    public boolean uploaded(
        long publicationId,
        long mediaId,
        UUID token,
        byte[] expectedSourceHash,
        String destinationMediaId,
        String destinationHash
    ) {
        Instant now = clock.instant();
        return dslContext.update(PUBLICATION_MEDIA)
            .set(PUBLICATION_MEDIA.STATUS, UPLOADED)
            .setNull(PUBLICATION_MEDIA.SKIP_REASON)
            .set(PUBLICATION_MEDIA.DESTINATION_MEDIA_ID, destinationMediaId)
            .set(PUBLICATION_MEDIA.DESTINATION_HASH, destinationHash)
            .set(PUBLICATION_MEDIA.COMPRESSION_PENDING, false)
            .setNull(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT)
            .setNull(PUBLICATION_MEDIA.LAST_ERROR)
            .setNull(PUBLICATION_MEDIA.FAILURE_TYPE)
            .setNull(PUBLICATION_MEDIA.FAILURE_STARTED_AT)
            .setNull(PUBLICATION_MEDIA.PROCESSING_TOKEN)
            .setNull(PUBLICATION_MEDIA.PROCESSING_UNTIL)
            .set(PUBLICATION_MEDIA.UPDATED_AT, jooqMappings.offsetDateTime(now))
            .where(claimMatches(publicationId, mediaId, token))
            .and(sourceHashMatches(mediaId, expectedSourceHash))
            .execute() == 1;
    }

    public boolean skipped(
        long publicationId,
        long mediaId,
        UUID token,
        byte[] expectedSourceHash,
        MediaSkipReason reason
    ) {
        Instant now = clock.instant();
        var databaseReason = org.moera.importer.persistence.jooq.enums.MediaSkipReason.valueOf(reason.name());
        return dslContext.update(PUBLICATION_MEDIA)
            .set(PUBLICATION_MEDIA.STATUS, SKIPPED)
            .set(PUBLICATION_MEDIA.SKIP_REASON, databaseReason)
            .setNull(PUBLICATION_MEDIA.DESTINATION_MEDIA_ID)
            .setNull(PUBLICATION_MEDIA.DESTINATION_HASH)
            .set(PUBLICATION_MEDIA.COMPRESSION_PENDING, false)
            .setNull(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT)
            .setNull(PUBLICATION_MEDIA.LAST_ERROR)
            .setNull(PUBLICATION_MEDIA.FAILURE_TYPE)
            .setNull(PUBLICATION_MEDIA.FAILURE_STARTED_AT)
            .setNull(PUBLICATION_MEDIA.PROCESSING_TOKEN)
            .setNull(PUBLICATION_MEDIA.PROCESSING_UNTIL)
            .set(PUBLICATION_MEDIA.UPDATED_AT, jooqMappings.offsetDateTime(now))
            .where(claimMatches(publicationId, mediaId, token))
            .and(sourceHashMatches(mediaId, expectedSourceHash))
            .execute() == 1;
    }

    public boolean retry(
        long publicationId,
        long mediaId,
        UUID token,
        Instant nextAttemptAt,
        String error,
        FailureSeries failureSeries
    ) {
        Instant now = clock.instant();
        return dslContext.update(PUBLICATION_MEDIA)
            .set(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT, jooqMappings.offsetDateTime(nextAttemptAt))
            .set(PUBLICATION_MEDIA.LAST_ERROR, error)
            .set(PUBLICATION_MEDIA.FAILURE_TYPE, failureSeries.type())
            .set(PUBLICATION_MEDIA.FAILURE_STARTED_AT, jooqMappings.offsetDateTime(failureSeries.startedAt()))
            .setNull(PUBLICATION_MEDIA.PROCESSING_TOKEN)
            .setNull(PUBLICATION_MEDIA.PROCESSING_UNTIL)
            .set(PUBLICATION_MEDIA.UPDATED_AT, jooqMappings.offsetDateTime(now))
            .where(claimMatches(publicationId, mediaId, token))
            .execute() == 1;
    }

    public boolean fail(
        long publicationId,
        long mediaId,
        UUID token,
        String error,
        FailureSeries failureSeries
    ) {
        Instant now = clock.instant();
        return dslContext.update(PUBLICATION_MEDIA)
            .setNull(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT)
            .set(PUBLICATION_MEDIA.LAST_ERROR, error)
            .set(PUBLICATION_MEDIA.FAILURE_TYPE, failureSeries != null ? failureSeries.type() : null)
            .set(
                PUBLICATION_MEDIA.FAILURE_STARTED_AT,
                failureSeries != null ? jooqMappings.offsetDateTime(failureSeries.startedAt()) : null
            )
            .setNull(PUBLICATION_MEDIA.PROCESSING_TOKEN)
            .setNull(PUBLICATION_MEDIA.PROCESSING_UNTIL)
            .set(PUBLICATION_MEDIA.UPDATED_AT, jooqMappings.offsetDateTime(now))
            .where(claimMatches(publicationId, mediaId, token))
            .execute() == 1;
    }

    public void synchronizeForMessage(long messageId, List<Long> mediaWithChangedSourceHash) {
        List<Long> publicationIds = dslContext.select(PUBLICATION_MESSAGES.PUBLICATION_ID)
            .from(PUBLICATION_MESSAGES)
            .where(PUBLICATION_MESSAGES.MESSAGE_ID.eq(messageId))
            .fetch(PUBLICATION_MESSAGES.PUBLICATION_ID);
        if (publicationIds.isEmpty()) {
            return;
        }
        for (Long publicationId : publicationIds) {
            synchronizeForPublication(publicationId);
        }
        if (!mediaWithChangedSourceHash.isEmpty()) {
            Instant now = clock.instant();
            dslContext.update(PUBLICATION_MEDIA)
                .set(PUBLICATION_MEDIA.STATUS, PENDING)
                .setNull(PUBLICATION_MEDIA.SKIP_REASON)
                .setNull(PUBLICATION_MEDIA.DESTINATION_MEDIA_ID)
                .setNull(PUBLICATION_MEDIA.DESTINATION_HASH)
                .set(PUBLICATION_MEDIA.COMPRESSION_PENDING, false)
                .set(PUBLICATION_MEDIA.ATTEMPTS, 0)
                .set(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT, jooqMappings.offsetDateTime(now))
                .setNull(PUBLICATION_MEDIA.LAST_ERROR)
                .setNull(PUBLICATION_MEDIA.FAILURE_TYPE)
                .setNull(PUBLICATION_MEDIA.FAILURE_STARTED_AT)
                .setNull(PUBLICATION_MEDIA.PROCESSING_TOKEN)
                .setNull(PUBLICATION_MEDIA.PROCESSING_UNTIL)
                .set(PUBLICATION_MEDIA.UPDATED_AT, jooqMappings.offsetDateTime(now))
                .where(PUBLICATION_MEDIA.MEDIA_ID.in(mediaWithChangedSourceHash))
                .and(PUBLICATION_MEDIA.PUBLICATION_ID.in(publicationIds))
                .execute();
        }
    }

    public void synchronizeForPublication(long publicationId) {
        Set<Long> effectiveMediaIds = effectiveMediaIds(publicationId);
        Set<Long> existingMediaIds = new HashSet<>(dslContext.select(PUBLICATION_MEDIA.MEDIA_ID)
            .from(PUBLICATION_MEDIA)
            .where(PUBLICATION_MEDIA.PUBLICATION_ID.eq(publicationId))
            .fetch(PUBLICATION_MEDIA.MEDIA_ID));

        dslContext.deleteFrom(PUBLICATION_MEDIA)
            .where(PUBLICATION_MEDIA.PUBLICATION_ID.eq(publicationId))
            .and(PUBLICATION_MEDIA.MEDIA_ID.notIn(effectiveMediaIds))
            .execute();

        Instant now = clock.instant();
        effectiveMediaIds.stream()
            .filter(mediaId -> !existingMediaIds.contains(mediaId))
            .forEach(mediaId -> dslContext.insertInto(PUBLICATION_MEDIA)
                .set(PUBLICATION_MEDIA.PUBLICATION_ID, publicationId)
                .set(PUBLICATION_MEDIA.MEDIA_ID, mediaId)
                .set(PUBLICATION_MEDIA.STATUS, PENDING)
                .set(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT, jooqMappings.offsetDateTime(now))
                .execute());
    }

    private Set<Long> effectiveMediaIds(long publicationId) {
        var previewRows = dslContext.select(MESSAGE_LINK_PREVIEWS.URL, MESSAGE_LINK_PREVIEWS.IMAGE_MEDIA_ID)
            .from(PUBLICATION_MESSAGES)
            .join(MESSAGES).on(PUBLICATION_MESSAGES.MESSAGE_ID.eq(MESSAGES.ID))
            .join(MESSAGE_LINK_PREVIEWS).on(MESSAGE_LINK_PREVIEWS.MESSAGE_ID.eq(MESSAGES.ID))
            .where(PUBLICATION_MESSAGES.PUBLICATION_ID.eq(publicationId))
            .and(MESSAGES.DELETED.isFalse())
            .orderBy(PUBLICATION_MESSAGES.POSITION, MESSAGE_LINK_PREVIEWS.POSITION)
            .fetch();
        Set<Long> previewMediaIds = new HashSet<>();
        Set<Long> effectivePreviewMediaIds = new HashSet<>();
        Set<String> seenUrls = new HashSet<>();
        previewRows.forEach(record -> {
            Long imageMediaId = record.get(MESSAGE_LINK_PREVIEWS.IMAGE_MEDIA_ID);
            if (imageMediaId != null) {
                previewMediaIds.add(imageMediaId);
            }
            if (seenUrls.add(record.get(MESSAGE_LINK_PREVIEWS.URL)) && imageMediaId != null) {
                effectivePreviewMediaIds.add(imageMediaId);
            }
        });

        Set<Long> result = new LinkedHashSet<>();
        dslContext.select(MEDIA.ID)
            .from(PUBLICATION_MESSAGES)
            .join(MESSAGES).on(PUBLICATION_MESSAGES.MESSAGE_ID.eq(MESSAGES.ID))
            .join(MEDIA).on(MEDIA.MESSAGE_ID.eq(MESSAGES.ID))
            .where(PUBLICATION_MESSAGES.PUBLICATION_ID.eq(publicationId))
            .and(MESSAGES.DELETED.isFalse())
            .orderBy(PUBLICATION_MESSAGES.POSITION, MEDIA.POSITION)
            .fetch(MEDIA.ID)
            .stream()
            .filter(mediaId -> !previewMediaIds.contains(mediaId) || effectivePreviewMediaIds.contains(mediaId))
            .forEach(result::add);
        return result;
    }

    private org.jooq.Condition claimMatches(long publicationId, long mediaId, UUID token) {
        return PUBLICATION_MEDIA.PUBLICATION_ID.eq(publicationId)
            .and(PUBLICATION_MEDIA.MEDIA_ID.eq(mediaId))
            .and(PUBLICATION_MEDIA.PROCESSING_TOKEN.eq(token));
    }

    private FailureSeries failureSeries(String type, java.time.OffsetDateTime startedAt) {
        return type != null && startedAt != null ? new FailureSeries(type, startedAt.toInstant()) : null;
    }

    private org.jooq.Condition sourceHashMatches(long mediaId, byte[] expectedSourceHash) {
        return exists(selectOne()
            .from(MEDIA)
            .where(MEDIA.ID.eq(mediaId))
            .and(MEDIA.SOURCE_HASH.eq(expectedSourceHash)));
    }

}
