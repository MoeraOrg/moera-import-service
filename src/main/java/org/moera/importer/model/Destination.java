package org.moera.importer.model;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

public record Destination(
    long id,
    String nodeName,
    JsonNode config,
    JsonNode data,
    Instant createdAt,
    Instant updatedAt
) {

    public Destination {
        Objects.requireNonNull(nodeName, "nodeName");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

}
