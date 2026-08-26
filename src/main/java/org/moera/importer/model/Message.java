package org.moera.importer.model;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

public record Message(
    long id,
    long channelId,
    String sourceId,
    int revision,
    boolean deleted,
    boolean pinned,
    Instant publishedAt,
    String body,
    BodyFormat bodyFormat,
    byte[] sourceBodyHash,
    JsonNode sourceData,
    Instant createdAt,
    Instant updatedAt
) {

    public Message {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(bodyFormat, "bodyFormat");
        sourceBodyHash = Objects.requireNonNull(sourceBodyHash, "sourceBodyHash").clone();
        Objects.requireNonNull(sourceData, "sourceData");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    @Override
    public byte[] sourceBodyHash() {
        return sourceBodyHash.clone();
    }

}
