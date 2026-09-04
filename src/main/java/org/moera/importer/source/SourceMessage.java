package org.moera.importer.source;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import org.moera.importer.model.BodyFormat;

public record SourceMessage(
    String sourceId,
    boolean deleted,
    boolean pinned,
    Instant publishedAt,
    String body,
    BodyFormat bodyFormat,
    byte[] sourceBodyHash,
    String externalSourceUri,
    ForwardAttribution forwardAttribution,
    JsonNode sourceData,
    List<SourceMedia> media,
    List<SourceLinkPreview> linkPreviews
) {

    public SourceMessage(
        String sourceId,
        boolean deleted,
        boolean pinned,
        Instant publishedAt,
        String body,
        BodyFormat bodyFormat,
        byte[] sourceBodyHash,
        JsonNode sourceData,
        List<SourceMedia> media,
        List<SourceLinkPreview> linkPreviews
    ) {
        this(sourceId, deleted, pinned, publishedAt, body, bodyFormat, sourceBodyHash, null, null, sourceData, media,
            linkPreviews);
    }

    public SourceMessage {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(bodyFormat, "bodyFormat");
        sourceBodyHash = Objects.requireNonNull(sourceBodyHash, "sourceBodyHash").clone();
        Objects.requireNonNull(sourceData, "sourceData");
        media = List.copyOf(Objects.requireNonNull(media, "media"));
        linkPreviews = List.copyOf(Objects.requireNonNull(linkPreviews, "linkPreviews"));
    }

    @Override
    public byte[] sourceBodyHash() {
        return sourceBodyHash.clone();
    }

}
