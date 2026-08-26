package org.moera.importer.source;

import java.time.Instant;
import java.util.Objects;

public record SourceLinkPreview(
    String siteName,
    String url,
    String title,
    String description,
    Instant publishedAt,
    String imageSourceMediaId
) {

    public SourceLinkPreview {
        Objects.requireNonNull(url, "url");
    }

}
