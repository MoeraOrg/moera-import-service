package org.moera.importer.persistence;

import java.time.Instant;

public record MessageLinkPreview(
    long messageId,
    int position,
    String siteName,
    String url,
    String title,
    String description,
    Instant publishedAt,
    Long imageMediaId
) {
}
