package org.moera.importer.persistence;

import java.util.Optional;
import jakarta.inject.Inject;

import org.jooq.DSLContext;
import org.moera.importer.model.SourceType;
import org.moera.importer.source.ChannelSnapshot;
import org.springframework.stereotype.Repository;

import static org.moera.importer.persistence.jooq.tables.Channels.CHANNELS;

@Repository
public class ChannelRepository {

    @Inject
    private DSLContext dslContext;

    @Inject
    private JooqMappings jooqMappings;

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

}
