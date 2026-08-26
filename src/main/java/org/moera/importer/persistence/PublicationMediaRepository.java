package org.moera.importer.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import static org.moera.importer.persistence.jooq.enums.PublicationMediaStatus.PENDING;
import static org.moera.importer.persistence.jooq.tables.Media.MEDIA;
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
        List<Long> mediaIds = dslContext.select(MEDIA.ID)
            .from(MEDIA)
            .where(MEDIA.MESSAGE_ID.eq(messageId))
            .fetch(MEDIA.ID);
        Instant now = clock.instant();
        for (Long publicationId : publicationIds) {
            for (Long mediaId : mediaIds) {
                dslContext.insertInto(PUBLICATION_MEDIA)
                    .set(PUBLICATION_MEDIA.PUBLICATION_ID, publicationId)
                    .set(PUBLICATION_MEDIA.MEDIA_ID, mediaId)
                    .set(PUBLICATION_MEDIA.STATUS, PENDING)
                    .set(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT, jooqMappings.offsetDateTime(now))
                    .onConflictDoNothing()
                    .execute();
            }
        }
        if (!mediaWithChangedSourceHash.isEmpty()) {
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
                .execute();
        }
    }

}
