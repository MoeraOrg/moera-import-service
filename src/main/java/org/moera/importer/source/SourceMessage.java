package org.moera.importer.source;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import org.moera.importer.model.BodyFormat;

public record SourceMessage(
    String sourceId,
    boolean deleted,
    Instant publishedAt,
    String body,
    BodyFormat bodyFormat,
    byte[] sourceBodyHash,
    JsonNode sourceData,
    List<SourceMedia> media
) {

    public SourceMessage {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(bodyFormat, "bodyFormat");
        sourceBodyHash = Objects.requireNonNull(sourceBodyHash, "sourceBodyHash").clone();
        Objects.requireNonNull(sourceData, "sourceData");
        media = List.copyOf(media);
    }

    @Override
    public byte[] sourceBodyHash() {
        return sourceBodyHash.clone();
    }

}
