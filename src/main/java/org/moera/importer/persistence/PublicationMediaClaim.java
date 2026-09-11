package org.moera.importer.persistence;

import java.util.UUID;

import org.moera.importer.application.FailureSeries;

public record PublicationMediaClaim(
    long publicationId,
    long mediaId,
    UUID processingToken,
    int attempts,
    FailureSeries failureSeries
) {

    public PublicationMediaClaim(long publicationId, long mediaId, UUID processingToken, int attempts) {
        this(publicationId, mediaId, processingToken, attempts, null);
    }

}
