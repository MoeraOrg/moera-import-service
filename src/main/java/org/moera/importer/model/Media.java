package org.moera.importer.model;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

public record Media(
    long id,
    long messageId,
    String sourceId,
    int position,
    String mimeType,
    String fileName,
    Long size,
    byte[] sourceHash,
    JsonNode sourceData,
    Instant createdAt,
    Instant updatedAt
) {

    public Media {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(mimeType, "mimeType");
        sourceHash = Objects.requireNonNull(sourceHash, "sourceHash").clone();
        Objects.requireNonNull(sourceData, "sourceData");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    @Override
    public byte[] sourceHash() {
        return sourceHash.clone();
    }

}
