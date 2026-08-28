package org.moera.importer.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import org.jooq.DSLContext;
import org.moera.importer.model.Message;
import org.moera.importer.observability.ImportMetrics;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import static org.moera.importer.persistence.jooq.tables.PublicationMessages.PUBLICATION_MESSAGES;
import static org.moera.importer.persistence.jooq.tables.PublicationMedia.PUBLICATION_MEDIA;
import static org.moera.importer.persistence.jooq.tables.Publications.PUBLICATIONS;
import static org.moera.importer.persistence.jooq.tables.Destinations.DESTINATIONS;
import static org.moera.importer.persistence.jooq.tables.Media.MEDIA;
import static org.moera.importer.persistence.jooq.tables.MessageLinkPreviews.MESSAGE_LINK_PREVIEWS;
import static org.moera.importer.persistence.jooq.tables.Messages.MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Mirrors.MIRRORS;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.when;
import static org.moera.importer.persistence.jooq.enums.PublicationMediaStatus.PENDING;
import static org.moera.importer.persistence.jooq.enums.PublicationMediaStatus.UPLOADED;

@Repository
public class PublicationRepository {

    @Inject
    private Clock clock;

    @Inject
    private DSLContext dslContext;

    @Inject
    private JooqMappings jooqMappings;

    @Inject
    private ImportMetrics importMetrics;

    @Inject
    private PublicationMediaRepository publicationMediaRepository;

    @Transactional
    public List<PublicationClaim> claimReady(int limit, Duration lease) {
        Instant now = clock.instant();
        var records = dslContext.select(
                PUBLICATIONS.ID,
                PUBLICATIONS.REVISION,
                PUBLICATIONS.ATTEMPTS,
                PUBLICATIONS.PROCESSING_UNTIL
            )
            .from(PUBLICATIONS)
            .where(PUBLICATIONS.REVISION.isDistinctFrom(PUBLICATIONS.PUBLISHED_REVISION))
            .and(PUBLICATIONS.NEXT_ATTEMPT_AT.le(jooqMappings.offsetDateTime(now)))
            .and(PUBLICATIONS.PROCESSING_UNTIL.isNull()
                .or(PUBLICATIONS.PROCESSING_UNTIL.le(jooqMappings.offsetDateTime(now))))
            .and(notExists(selectOne()
                .from(PUBLICATION_MEDIA)
                .where(PUBLICATION_MEDIA.PUBLICATION_ID.eq(PUBLICATIONS.ID))
                .and(PUBLICATION_MEDIA.STATUS.eq(PENDING))))
            .orderBy(PUBLICATIONS.NEXT_ATTEMPT_AT, PUBLICATIONS.ID)
            .limit(limit)
            .forUpdate()
            .skipLocked()
            .fetch();
        importMetrics.leaseReclaimed(
            "publication",
            (int) records.stream().filter(record -> record.get(PUBLICATIONS.PROCESSING_UNTIL) != null).count()
        );
        return records.stream().map(record -> {
            long publicationId = record.get(PUBLICATIONS.ID);
            int targetRevision = record.get(PUBLICATIONS.REVISION);
            int attempts = record.get(PUBLICATIONS.ATTEMPTS) + 1;
            UUID processingToken = UUID.randomUUID();
            dslContext.update(PUBLICATIONS)
                .set(PUBLICATIONS.ATTEMPTS, attempts)
                .set(PUBLICATIONS.PROCESSING_TOKEN, processingToken)
                .set(PUBLICATIONS.PROCESSING_UNTIL, jooqMappings.offsetDateTime(now.plus(lease)))
                .set(PUBLICATIONS.UPDATED_AT, jooqMappings.offsetDateTime(now))
                .where(PUBLICATIONS.ID.eq(publicationId))
                .execute();
            return new PublicationClaim(publicationId, targetRevision, processingToken, attempts);
        }).toList();
    }

    @Transactional
    public PublicationContext loadContext(long publicationId, int targetRevision) {
        var publication = dslContext.selectFrom(PUBLICATIONS)
            .where(PUBLICATIONS.ID.eq(publicationId))
            .forUpdate()
            .fetchSingle();
        if (publication.getRevision() != targetRevision) {
            throw new PublicationSnapshotChangedException();
        }
        var mirror = dslContext.selectFrom(MIRRORS)
            .where(MIRRORS.ID.eq(publication.getMirrorId()))
            .fetchSingle();
        var destination = dslContext.selectFrom(DESTINATIONS)
            .where(DESTINATIONS.ID.eq(mirror.getDestinationId()))
            .fetchSingle();

        List<Message> messages = dslContext.select(MESSAGES.fields())
            .from(PUBLICATION_MESSAGES)
            .join(MESSAGES).on(MESSAGES.ID.eq(PUBLICATION_MESSAGES.MESSAGE_ID))
            .where(PUBLICATION_MESSAGES.PUBLICATION_ID.eq(publicationId))
            .orderBy(PUBLICATION_MESSAGES.POSITION)
            .fetch(record -> jooqMappings.message(record.into(MESSAGES)));
        Map<Long, List<MessageLinkPreview>> previewsByMessage = loadPreviews(messages);
        List<PublicationContext.Member> members = messages.stream()
            .map(message -> new PublicationContext.Member(
                message, previewsByMessage.getOrDefault(message.id(), List.of())
            ))
            .toList();

        List<PublicationContext.DeliveredMedia> media = dslContext.select(MEDIA.fields())
            .select(PUBLICATION_MEDIA.DESTINATION_MEDIA_ID, PUBLICATION_MEDIA.DESTINATION_DATA)
            .from(PUBLICATION_MEDIA)
            .join(MEDIA).on(MEDIA.ID.eq(PUBLICATION_MEDIA.MEDIA_ID))
            .join(PUBLICATION_MESSAGES)
                .on(PUBLICATION_MESSAGES.PUBLICATION_ID.eq(PUBLICATION_MEDIA.PUBLICATION_ID))
                .and(PUBLICATION_MESSAGES.MESSAGE_ID.eq(MEDIA.MESSAGE_ID))
            .where(PUBLICATION_MEDIA.PUBLICATION_ID.eq(publicationId))
            .and(PUBLICATION_MEDIA.STATUS.eq(UPLOADED))
            .orderBy(PUBLICATION_MESSAGES.POSITION, MEDIA.POSITION)
            .fetch(record -> {
                JsonNode data = jooqMappings.jsonNode(record.get(PUBLICATION_MEDIA.DESTINATION_DATA));
                return new PublicationContext.DeliveredMedia(
                    jooqMappings.media(record.into(MEDIA)),
                    record.get(PUBLICATION_MEDIA.DESTINATION_MEDIA_ID),
                    requiredMediaHash(data)
                );
            });
        return new PublicationContext(
            publicationId,
            targetRevision,
            jooqMappings.destination(destination),
            jooqMappings.jsonNode(mirror.getConfig()),
            publication.getDestinationPostId(),
            jooqMappings.jsonNode(publication.getDestinationData()),
            members,
            media
        );
    }

    public boolean created(
        long publicationId,
        UUID token,
        int targetRevision,
        String postingId,
        JsonNode destinationData
    ) {
        return complete(publicationId, token, targetRevision, postingId, true, destinationData);
    }

    public boolean updated(
        long publicationId,
        UUID token,
        int targetRevision,
        JsonNode destinationData
    ) {
        return complete(publicationId, token, targetRevision, null, false, destinationData);
    }

    public boolean deleted(
        long publicationId,
        UUID token,
        int targetRevision,
        JsonNode destinationData
    ) {
        return complete(publicationId, token, targetRevision, null, true, destinationData);
    }

    public boolean retry(
        long publicationId,
        UUID token,
        Instant nextAttemptAt,
        String error
    ) {
        Instant now = clock.instant();
        return dslContext.update(PUBLICATIONS)
            .set(PUBLICATIONS.NEXT_ATTEMPT_AT, jooqMappings.offsetDateTime(nextAttemptAt))
            .set(PUBLICATIONS.LAST_ERROR, error)
            .setNull(PUBLICATIONS.PROCESSING_TOKEN)
            .setNull(PUBLICATIONS.PROCESSING_UNTIL)
            .set(PUBLICATIONS.UPDATED_AT, jooqMappings.offsetDateTime(now))
            .where(PUBLICATIONS.ID.eq(publicationId))
            .and(PUBLICATIONS.PROCESSING_TOKEN.eq(token))
            .execute() == 1;
    }

    public boolean deferCreateRate(long publicationId, UUID token, Instant nextAttemptAt) {
        Instant now = clock.instant();
        return dslContext.update(PUBLICATIONS)
            .set(
                PUBLICATIONS.ATTEMPTS,
                when(PUBLICATIONS.ATTEMPTS.gt(0), PUBLICATIONS.ATTEMPTS.minus(1)).otherwise(0)
            )
            .set(PUBLICATIONS.NEXT_ATTEMPT_AT, jooqMappings.offsetDateTime(nextAttemptAt))
            .setNull(PUBLICATIONS.PROCESSING_TOKEN)
            .setNull(PUBLICATIONS.PROCESSING_UNTIL)
            .set(PUBLICATIONS.UPDATED_AT, jooqMappings.offsetDateTime(now))
            .where(PUBLICATIONS.ID.eq(publicationId))
            .and(PUBLICATIONS.PROCESSING_TOKEN.eq(token))
            .execute() == 1;
    }

    public boolean fail(long publicationId, UUID token, String error) {
        Instant now = clock.instant();
        return dslContext.update(PUBLICATIONS)
            .setNull(PUBLICATIONS.NEXT_ATTEMPT_AT)
            .set(PUBLICATIONS.LAST_ERROR, error)
            .setNull(PUBLICATIONS.PROCESSING_TOKEN)
            .setNull(PUBLICATIONS.PROCESSING_UNTIL)
            .set(PUBLICATIONS.UPDATED_AT, jooqMappings.offsetDateTime(now))
            .where(PUBLICATIONS.ID.eq(publicationId))
            .and(PUBLICATIONS.PROCESSING_TOKEN.eq(token))
            .execute() == 1;
    }

    @Transactional
    public void create(long mirrorId, List<Message> orderedMessages) {
        if (orderedMessages.isEmpty()) {
            throw new IllegalArgumentException("A publication requires at least one message");
        }
        Instant now = clock.instant();
        long publicationId = dslContext.insertInto(PUBLICATIONS)
            .set(PUBLICATIONS.MIRROR_ID, mirrorId)
            .set(PUBLICATIONS.REVISION, 0)
            .setNull(PUBLICATIONS.PUBLISHED_REVISION)
            .set(PUBLICATIONS.NEXT_ATTEMPT_AT, jooqMappings.offsetDateTime(now))
            .returningResult(PUBLICATIONS.ID)
            .fetchSingle(PUBLICATIONS.ID);
        for (int position = 0; position < orderedMessages.size(); position++) {
            dslContext.insertInto(PUBLICATION_MESSAGES)
                .set(PUBLICATION_MESSAGES.PUBLICATION_ID, publicationId)
                .set(PUBLICATION_MESSAGES.MESSAGE_ID, orderedMessages.get(position).id())
                .set(PUBLICATION_MESSAGES.POSITION, position)
                .execute();
        }
        publicationMediaRepository.synchronizeForPublication(publicationId);
    }

    public void reviseForMessage(long messageId) {
        Instant now = clock.instant();
        dslContext.update(PUBLICATIONS)
            .set(PUBLICATIONS.REVISION, PUBLICATIONS.REVISION.plus(1))
            .set(PUBLICATIONS.ATTEMPTS, 0)
            .setNull(PUBLICATIONS.LAST_ERROR)
            .set(PUBLICATIONS.NEXT_ATTEMPT_AT, jooqMappings.offsetDateTime(now))
            .set(PUBLICATIONS.UPDATED_AT, jooqMappings.offsetDateTime(now))
            .where(PUBLICATIONS.ID.in(dslContext.select(PUBLICATION_MESSAGES.PUBLICATION_ID)
                .from(PUBLICATION_MESSAGES)
                .where(PUBLICATION_MESSAGES.MESSAGE_ID.eq(messageId))))
            .execute();
    }

    private Map<Long, List<MessageLinkPreview>> loadPreviews(List<Message> messages) {
        if (messages.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<MessageLinkPreview>> previews = new LinkedHashMap<>();
        dslContext.selectFrom(MESSAGE_LINK_PREVIEWS)
            .where(MESSAGE_LINK_PREVIEWS.MESSAGE_ID.in(messages.stream().map(Message::id).toList()))
            .orderBy(MESSAGE_LINK_PREVIEWS.MESSAGE_ID, MESSAGE_LINK_PREVIEWS.POSITION)
            .forEach(record -> previews.computeIfAbsent(record.getMessageId(), ignored -> new ArrayList<>()).add(
                new MessageLinkPreview(
                    record.getMessageId(),
                    record.getPosition(),
                    record.getSiteName(),
                    record.getUrl(),
                    record.getTitle(),
                    record.getDescription(),
                    jooqMappings.instant(record.getPublishedAt()),
                    record.getImageMediaId()
                )
            ));
        previews.replaceAll((messageId, values) -> List.copyOf(values));
        return Map.copyOf(previews);
    }

    private String requiredMediaHash(JsonNode data) {
        JsonNode hash = data.get("hash");
        if (hash == null || !hash.isTextual() || hash.textValue().isBlank()) {
            throw new IllegalStateException("Uploaded publication media has no destination hash");
        }
        return hash.textValue();
    }

    private boolean complete(
        long publicationId,
        UUID token,
        int targetRevision,
        String destinationPostId,
        boolean replaceDestinationPostId,
        JsonNode destinationData
    ) {
        Instant now = clock.instant();
        var update = dslContext.update(PUBLICATIONS)
            .set(PUBLICATIONS.PUBLISHED_REVISION, targetRevision)
            .set(PUBLICATIONS.DESTINATION_DATA, jooqMappings.json(destinationData))
            .set(PUBLICATIONS.ATTEMPTS, 0)
            .set(
                PUBLICATIONS.NEXT_ATTEMPT_AT,
                when(PUBLICATIONS.REVISION.eq(targetRevision), (java.time.OffsetDateTime) null)
                    .otherwise(jooqMappings.offsetDateTime(now))
            )
            .setNull(PUBLICATIONS.LAST_ERROR)
            .setNull(PUBLICATIONS.PROCESSING_TOKEN)
            .setNull(PUBLICATIONS.PROCESSING_UNTIL)
            .set(PUBLICATIONS.UPDATED_AT, jooqMappings.offsetDateTime(now));
        if (replaceDestinationPostId) {
            update.set(PUBLICATIONS.DESTINATION_POST_ID, destinationPostId);
        }
        return update.where(PUBLICATIONS.ID.eq(publicationId))
            .and(PUBLICATIONS.PROCESSING_TOKEN.eq(token))
            .execute() == 1;
    }

}
