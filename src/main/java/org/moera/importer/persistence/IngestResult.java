package org.moera.importer.persistence;

public record IngestResult(long messageId, int revision, boolean created, boolean changed) {
}
