package org.moera.importer.model;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

public record Mirror(
    long id,
    long channelId,
    long destinationId,
    boolean enabled,
    JsonNode config,
    Instant createdAt,
    Instant updatedAt
) {

    public Mirror {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

}
