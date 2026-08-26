package org.moera.importer.persistence;

import java.util.UUID;

public record PublicationClaim(long publicationId, int targetRevision, UUID processingToken, int attempts) {
}
