package org.moera.importer.persistence;

import java.util.UUID;

import org.moera.importer.application.FailureSeries;

public record PublicationClaim(
    long publicationId,
    int targetRevision,
    UUID processingToken,
    int attempts,
    FailureSeries failureSeries
) {

    public PublicationClaim(long publicationId, int targetRevision, UUID processingToken, int attempts) {
        this(publicationId, targetRevision, processingToken, attempts, null);
    }

}
