package org.moera.importer.persistence;

import java.util.List;
import java.util.Optional;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import org.jooq.DSLContext;
import org.moera.importer.model.Destination;
import org.moera.importer.persistence.jooq.tables.records.DestinationsRecord;
import org.springframework.stereotype.Repository;

import static org.moera.importer.persistence.jooq.tables.Destinations.DESTINATIONS;

@Repository
public class DestinationRepository {

    @Inject
    private DSLContext dslContext;

    @Inject
    private JooqMappings jooqMappings;

    public Optional<Destination> find(long id) {
        return dslContext.selectFrom(DESTINATIONS)
            .where(DESTINATIONS.ID.eq(id))
            .fetchOptional()
            .map(jooqMappings::destination);
    }

    public List<Destination> findAll() {
        return dslContext.selectFrom(DESTINATIONS)
            .orderBy(DESTINATIONS.ID)
            .fetch(jooqMappings::destination);
    }

    public Destination create(String nodeName, JsonNode config) {
        DestinationsRecord record = dslContext.insertInto(DESTINATIONS)
            .set(DESTINATIONS.NODE_NAME, nodeName)
            .set(DESTINATIONS.CONFIG, jooqMappings.json(config))
            .returning()
            .fetchSingle();
        return jooqMappings.destination(record);
    }

    public boolean delete(long id) {
        return dslContext.deleteFrom(DESTINATIONS)
            .where(DESTINATIONS.ID.eq(id))
            .execute() == 1;
    }

}
