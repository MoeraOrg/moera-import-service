package org.moera.importer.persistence;

import java.util.UUID;

public record PublicationMediaClaim(long publicationId, long mediaId, UUID processingToken, int attempts) {
}
