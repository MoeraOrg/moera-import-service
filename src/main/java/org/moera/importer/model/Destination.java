package org.moera.importer.model;

import java.time.Instant;
import java.util.Objects;

public record Destination(
    long id,
    String nodeName,
    boolean profileSync,
    String profileFingerprint,
    String profileSourceEmblemId,
    String avatarId,
    String avatarMediaId,
    String avatarShape,
    Instant createdAt,
    Instant updatedAt
) {

    public Destination(long id, String nodeName, Instant createdAt, Instant updatedAt) {
        this(id, nodeName, true, null, null, null, null, null, createdAt, updatedAt);
    }

    public Destination {
        Objects.requireNonNull(nodeName, "nodeName");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

}
