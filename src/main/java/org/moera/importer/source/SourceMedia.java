package org.moera.importer.source;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

public record SourceMedia(
    String sourceId,
    int position,
    String mimeType,
    String fileName,
    Long size,
    byte[] sourceHash,
    JsonNode sourceData
) {

    public SourceMedia {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(mimeType, "mimeType");
        sourceHash = Objects.requireNonNull(sourceHash, "sourceHash").clone();
        Objects.requireNonNull(sourceData, "sourceData");
    }

    @Override
    public byte[] sourceHash() {
        return sourceHash.clone();
    }

}
