package org.moera.importer.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.jooq.DSLContext;
import org.moera.importer.model.MediaSkipReason;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    @Inject
    private Clock clock;

    @Inject
    private DSLContext dslContext;

    @Inject
    private JooqMappings jooqMappings;

    @Transactional
    public List<PublicationMediaClaim> claimPending(int limit, Duration lease) {
        Instant now = clock.instant();
        var records = dslContext.select(
                PUBLICATION_MEDIA.PUBLICATION_ID,
                PUBLICATION_MEDIA.MEDIA_ID,
                PUBLICATION_MEDIA.ATTEMPTS
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
        return records.stream().map(record -> {
            long publicationId = record.get(PUBLICATION_MEDIA.PUBLICATION_ID);
            long mediaId = record.get(PUBLICATION_MEDIA.MEDIA_ID);
            int attempts = record.get(PUBLICATION_MEDIA.ATTEMPTS) + 1;
            UUID processingToken = UUID.randomUUID();
            dslContext.update(PUBLICATION_MEDIA)
                .set(PUBLICATION_MEDIA.ATTEMPTS, attempts)
                .set(PUBLICATION_MEDIA.PROCESSING_TOKEN, processingToken)
                .set(PUBLICATION_MEDIA.PROCESSING_UNTIL, jooqMappings.offsetDateTime(now.plus(lease)))
                .set(PUBLICATION_MEDIA.UPDATED_AT, jooqMappings.offsetDateTime(now))
                .where(PUBLICATION_MEDIA.PUBLICATION_ID.eq(publicationId))
                .and(PUBLICATION_MEDIA.MEDIA_ID.eq(mediaId))
                .execute();
            return new PublicationMediaClaim(publicationId, mediaId, processingToken, attempts);
        }).toList();
    }

    public PublicationMediaContext loadContext(long publicationId, long mediaId) {
        var record = dslContext.select(
                PUBLICATION_MEDIA.DESTINATION_MEDIA_ID,
                PUBLICATION_MEDIA.DESTINATION_DATA
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
            jooqMappings.jsonNode(record.get(PUBLICATION_MEDIA.DESTINATION_DATA))
        );
    }

    public boolean awaitingCompression(
        long publicationId,
        long mediaId,
        UUID token,
        byte[] expectedSourceHash,
        String originalMediaId,
        JsonNode destinationData,
        Instant nextAttemptAt
    ) {
        if (!(destinationData instanceof ObjectNode objectNode)) {
            throw new IllegalArgumentException("destinationData must be a JSON object");
        }
        ObjectNode waitingData = objectNode.deepCopy().put("compressionPending", true);
        Instant now = clock.instant();
        return dslContext.update(PUBLICATION_MEDIA)
            .set(PUBLICATION_MEDIA.STATUS, PENDING)
            .setNull(PUBLICATION_MEDIA.SKIP_REASON)
            .set(PUBLICATION_MEDIA.DESTINATION_MEDIA_ID, originalMediaId)
            .set(PUBLICATION_MEDIA.DESTINATION_DATA, jooqMappings.json(waitingData))
            .set(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT, jooqMappings.offsetDateTime(nextAttemptAt))
            .setNull(PUBLICATION_MEDIA.LAST_ERROR)
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
        JsonNode destinationData
    ) {
        Instant now = clock.instant();
        return dslContext.update(PUBLICATION_MEDIA)
            .set(PUBLICATION_MEDIA.STATUS, UPLOADED)
            .setNull(PUBLICATION_MEDIA.SKIP_REASON)
            .set(PUBLICATION_MEDIA.DESTINATION_MEDIA_ID, destinationMediaId)
            .set(PUBLICATION_MEDIA.DESTINATION_DATA, jooqMappings.json(destinationData))
            .setNull(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT)
            .setNull(PUBLICATION_MEDIA.LAST_ERROR)
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
            .set(PUBLICATION_MEDIA.DESTINATION_DATA, jooqMappings.json(JsonNodeFactory.instance.objectNode()))
            .setNull(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT)
            .setNull(PUBLICATION_MEDIA.LAST_ERROR)
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
        String error
    ) {
        Instant now = clock.instant();
        return dslContext.update(PUBLICATION_MEDIA)
            .set(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT, jooqMappings.offsetDateTime(nextAttemptAt))
            .set(PUBLICATION_MEDIA.LAST_ERROR, error)
            .setNull(PUBLICATION_MEDIA.PROCESSING_TOKEN)
            .setNull(PUBLICATION_MEDIA.PROCESSING_UNTIL)
            .set(PUBLICATION_MEDIA.UPDATED_AT, jooqMappings.offsetDateTime(now))
            .where(claimMatches(publicationId, mediaId, token))
            .execute() == 1;
    }

    public boolean fail(long publicationId, long mediaId, UUID token, String error) {
        Instant now = clock.instant();
        return dslContext.update(PUBLICATION_MEDIA)
            .setNull(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT)
            .set(PUBLICATION_MEDIA.LAST_ERROR, error)
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
                .set(PUBLICATION_MEDIA.DESTINATION_DATA, jooqMappings.json(JsonNodeFactory.instance.objectNode()))
                .set(PUBLICATION_MEDIA.ATTEMPTS, 0)
                .set(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT, jooqMappings.offsetDateTime(now))
                .setNull(PUBLICATION_MEDIA.LAST_ERROR)
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

    private org.jooq.Condition sourceHashMatches(long mediaId, byte[] expectedSourceHash) {
        return exists(selectOne()
            .from(MEDIA)
            .where(MEDIA.ID.eq(mediaId))
            .and(MEDIA.SOURCE_HASH.eq(expectedSourceHash)));
    }

}
