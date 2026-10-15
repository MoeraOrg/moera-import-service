package org.moera.importer.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record RepublishState(
    String nodeName, String postingId, Instant requestedAt, List<String> lookupUris, boolean deleting
) {

    public static final Duration POLL_INTERVAL = Duration.ofSeconds(15);
    public static final Duration MAX_WAIT = Duration.ofMinutes(30);

    public RepublishState(String nodeName, String postingId, Instant requestedAt, List<String> lookupUris) {
        this(nodeName, postingId, requestedAt, lookupUris, false);
    }

    public RepublishState {
        Objects.requireNonNull(nodeName, "nodeName");
        Objects.requireNonNull(postingId, "postingId");
        lookupUris = List.copyOf(lookupUris);
    }

}
