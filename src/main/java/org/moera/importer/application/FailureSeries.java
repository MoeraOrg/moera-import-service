package org.moera.importer.application;

import java.time.Instant;

public record FailureSeries(String type, Instant startedAt) {
}
