package org.moera.importer.persistence;

import java.util.Optional;
import jakarta.inject.Inject;

import org.jooq.DSLContext;
import org.moera.importer.model.Destination;
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

}
