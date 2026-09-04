package org.moera.importer.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record Mirror(
    long id,
    long channelId,
    long destinationId,
    boolean enabled,
    List<String> feeds,
    Instant createdAt,
    Instant updatedAt
) {

    public Mirror {
        feeds = List.copyOf(Objects.requireNonNull(feeds, "feeds"));
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

}
