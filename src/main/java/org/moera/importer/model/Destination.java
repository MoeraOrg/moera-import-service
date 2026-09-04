package org.moera.importer.model;

import java.time.Instant;
import java.util.Objects;

public record Destination(
    long id,
    String nodeName,
    String credentialRef,
    boolean profileSync,
    String profileFingerprint,
    String profileSourceEmblemId,
    String avatarId,
    String avatarMediaId,
    String avatarShape,
    Instant createdAt,
    Instant updatedAt
) {

    public Destination(long id, String nodeName, String credentialRef, Instant createdAt, Instant updatedAt) {
        this(id, nodeName, credentialRef, true, null, null, null, null, null, createdAt, updatedAt);
    }

    public Destination {
        Objects.requireNonNull(nodeName, "nodeName");
        Objects.requireNonNull(credentialRef, "credentialRef");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

}
