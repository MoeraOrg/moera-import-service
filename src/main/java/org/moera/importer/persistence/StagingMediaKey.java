package org.moera.importer.persistence;

public record StagingMediaKey(long publicationId, long mediaId, String sourceHash) {
}
