package org.moera.importer.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import static org.moera.importer.persistence.jooq.enums.PublicationMediaStatus.PENDING;
import static org.moera.importer.persistence.jooq.tables.Media.MEDIA;
import static org.moera.importer.persistence.jooq.tables.MessageLinkPreviews.MESSAGE_LINK_PREVIEWS;
import static org.moera.importer.persistence.jooq.tables.Messages.MESSAGES;
import static org.moera.importer.persistence.jooq.tables.PublicationMedia.PUBLICATION_MEDIA;
import static org.moera.importer.persistence.jooq.tables.PublicationMessages.PUBLICATION_MESSAGES;

@Repository
public class PublicationMediaRepository {

    @Inject
    private Clock clock;

    @Inject
    private DSLContext dslContext;

    @Inject
    private JooqMappings jooqMappings;

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

}
