package org.moera.importer.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import jakarta.inject.Inject;

import org.jooq.DSLContext;
import org.moera.importer.model.Message;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import static org.moera.importer.persistence.jooq.enums.PublicationMediaStatus.PENDING;
import static org.moera.importer.persistence.jooq.tables.Media.MEDIA;
import static org.moera.importer.persistence.jooq.tables.PublicationMedia.PUBLICATION_MEDIA;
import static org.moera.importer.persistence.jooq.tables.PublicationMessages.PUBLICATION_MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Publications.PUBLICATIONS;

@Repository
public class PublicationRepository {

    @Inject
    private Clock clock;

    @Inject
    private DSLContext dslContext;

    @Inject
    private JooqMappings jooqMappings;

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
        List<Long> messageIds = orderedMessages.stream().map(Message::id).toList();
        dslContext.select(MEDIA.ID)
            .from(MEDIA)
            .where(MEDIA.MESSAGE_ID.in(messageIds))
            .orderBy(MEDIA.MESSAGE_ID, MEDIA.POSITION)
            .fetch(MEDIA.ID)
            .forEach(mediaId -> dslContext.insertInto(PUBLICATION_MEDIA)
                .set(PUBLICATION_MEDIA.PUBLICATION_ID, publicationId)
                .set(PUBLICATION_MEDIA.MEDIA_ID, mediaId)
                .set(PUBLICATION_MEDIA.STATUS, PENDING)
                .set(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT, jooqMappings.offsetDateTime(now))
                .execute());
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

}
