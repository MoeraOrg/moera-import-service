package org.moera.importer.persistence;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jooq.DSLContext;
import org.moera.importer.model.Destination;
import org.moera.importer.persistence.jooq.tables.records.DestinationsRecord;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import static org.jooq.impl.DSL.currentOffsetDateTime;
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

    @Transactional
    public boolean saveProfileState(long destinationId, ObjectNode profileState) {
        DestinationsRecord record = dslContext.selectFrom(DESTINATIONS)
            .where(DESTINATIONS.ID.eq(destinationId))
            .forUpdate()
            .fetchOne();
        if (record == null) {
            return false;
        }
        JsonNode stored = jooqMappings.jsonNode(record.getData());
        ObjectNode data = stored instanceof ObjectNode objectNode
            ? objectNode.deepCopy()
            : JsonNodeFactory.instance.objectNode();
        data.set("profile", profileState.deepCopy());
        return dslContext.update(DESTINATIONS)
            .set(DESTINATIONS.DATA, jooqMappings.json(data))
            .set(DESTINATIONS.UPDATED_AT, currentOffsetDateTime())
            .where(DESTINATIONS.ID.eq(destinationId))
            .execute() == 1;
    }

    @Transactional
    public DestinationCreateSlot acquireCreateSlot(long destinationId, Duration interval) {
        DestinationsRecord destination = dslContext.selectFrom(DESTINATIONS)
            .where(DESTINATIONS.ID.eq(destinationId))
            .forUpdate()
            .fetchSingle();
        OffsetDateTime databaseNow = dslContext.select(currentOffsetDateTime()).fetchSingle().value1();
        OffsetDateTime next = destination.getNextCreateAt();
        if (next != null && next.isAfter(databaseNow)) {
            return new DestinationCreateSlot.Deferred(next.toInstant());
        }
        dslContext.update(DESTINATIONS)
            .set(DESTINATIONS.NEXT_CREATE_AT, databaseNow.plus(interval))
            .where(DESTINATIONS.ID.eq(destinationId))
            .execute();
        return new DestinationCreateSlot.Acquired();
    }

}
