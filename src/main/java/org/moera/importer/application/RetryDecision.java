package org.moera.importer.application;

import java.time.Instant;

public record RetryDecision(boolean fatal, Instant nextAttemptAt, FailureSeries series) {
}
