package org.moera.importer.persistence;

import java.util.List;
import java.util.Optional;
import jakarta.inject.Inject;

import org.jooq.DSLContext;
import org.moera.importer.model.Mirror;
import org.moera.importer.model.Destination;
import org.moera.importer.persistence.jooq.tables.Mirrors;
import org.moera.importer.persistence.jooq.tables.records.MirrorsRecord;
import org.springframework.stereotype.Repository;

import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.min;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;
import static org.moera.importer.persistence.jooq.tables.Messages.MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Mirrors.MIRRORS;
import static org.moera.importer.persistence.jooq.tables.PublicationMessages.PUBLICATION_MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Publications.PUBLICATIONS;
import static org.moera.importer.persistence.jooq.tables.Destinations.DESTINATIONS;

@Repository
public class MirrorRepository {

    @Inject
    private DSLContext dslContext;

    @Inject
    private JooqMappings jooqMappings;

    public List<Long> findWithUnassignedMessages(int limit) {
        return dslContext.select(MIRRORS.ID)
            .from(MIRRORS)
            .where(MIRRORS.ENABLED.isTrue())
            .and(exists(selectOne()
                .from(MESSAGES)
                .where(MESSAGES.CHANNEL_ID.eq(MIRRORS.CHANNEL_ID))
                .and(notExists(selectOne()
                    .from(PUBLICATION_MESSAGES)
                    .join(PUBLICATIONS).on(PUBLICATION_MESSAGES.PUBLICATION_ID.eq(PUBLICATIONS.ID))
                    .where(PUBLICATION_MESSAGES.MESSAGE_ID.eq(MESSAGES.ID))
                    .and(PUBLICATIONS.MIRROR_ID.eq(MIRRORS.ID))))))
            .orderBy(MIRRORS.ID)
            .limit(limit)
            .fetch(MIRRORS.ID);
    }

    public Optional<Mirror> lockForBuild(long mirrorId) {
        return dslContext.selectFrom(MIRRORS)
            .where(MIRRORS.ID.eq(mirrorId))
            .and(MIRRORS.ENABLED.isTrue())
            .forUpdate()
            .skipLocked()
            .fetchOptional()
            .map(jooqMappings::mirror);
    }

    public List<Destination> findProfileDestinations(long channelId) {
        Mirrors profileOwner = MIRRORS.as("profile_owner");
        return dslContext.select(DESTINATIONS)
            .from(MIRRORS)
            .join(DESTINATIONS).on(DESTINATIONS.ID.eq(MIRRORS.DESTINATION_ID))
            .where(MIRRORS.CHANNEL_ID.eq(channelId))
            .and(MIRRORS.ENABLED.isTrue())
            .and(DESTINATIONS.PROFILE_SYNC.isTrue())
            .and(MIRRORS.ID.eq(dslContext.select(min(profileOwner.ID))
                .from(profileOwner)
                .where(profileOwner.DESTINATION_ID.eq(MIRRORS.DESTINATION_ID))
                .and(profileOwner.ENABLED.isTrue())))
            .orderBy(DESTINATIONS.ID)
            .fetch(record -> jooqMappings.destination(record.value1()));
    }

    public boolean hasMirror(long destinationId) {
        return dslContext.fetchExists(
            dslContext.selectOne().from(MIRRORS).where(MIRRORS.DESTINATION_ID.eq(destinationId))
        );
    }

    public Mirror create(long channelId, long destinationId, List<String> feeds) {
        MirrorsRecord record = dslContext.insertInto(MIRRORS)
            .set(MIRRORS.CHANNEL_ID, channelId)
            .set(MIRRORS.DESTINATION_ID, destinationId)
            .set(MIRRORS.FEEDS, feeds.toArray(String[]::new))
            .returning()
            .fetchSingle();
        return jooqMappings.mirror(record);
    }

    public boolean delete(long channelId, long destinationId) {
        return dslContext.deleteFrom(MIRRORS)
            .where(MIRRORS.CHANNEL_ID.eq(channelId))
            .and(MIRRORS.DESTINATION_ID.eq(destinationId))
            .execute() == 1;
    }

}
