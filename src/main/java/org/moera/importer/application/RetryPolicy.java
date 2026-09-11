package org.moera.importer.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

public final class RetryPolicy {

    private static final Duration DEFAULT_FATAL_AFTER = Duration.ofDays(1);

    private final Duration baseDelay;
    private final Duration maxDelay;
    private final Duration fatalAfter;
    private final double jitterFraction;
    private final DoubleSupplier jitterSupplier;

    public RetryPolicy(Duration baseDelay, Duration maxDelay, double jitterFraction) {
        this(baseDelay, maxDelay, DEFAULT_FATAL_AFTER, jitterFraction, ThreadLocalRandom.current()::nextDouble);
    }

    public RetryPolicy(Duration baseDelay, Duration maxDelay, Duration fatalAfter, double jitterFraction) {
        this(baseDelay, maxDelay, fatalAfter, jitterFraction, ThreadLocalRandom.current()::nextDouble);
    }

    RetryPolicy(
        Duration baseDelay,
        Duration maxDelay,
        double jitterFraction,
        DoubleSupplier jitterSupplier
    ) {
        this(baseDelay, maxDelay, DEFAULT_FATAL_AFTER, jitterFraction, jitterSupplier);
    }

    RetryPolicy(
        Duration baseDelay,
        Duration maxDelay,
        Duration fatalAfter,
        double jitterFraction,
        DoubleSupplier jitterSupplier
    ) {
        this.baseDelay = requirePositive(baseDelay, "baseDelay");
        this.maxDelay = requirePositive(maxDelay, "maxDelay");
        this.fatalAfter = requirePositive(fatalAfter, "fatalAfter");
        if (this.maxDelay.compareTo(this.baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must not be shorter than baseDelay");
        }
        if (!Double.isFinite(jitterFraction) || jitterFraction < 0 || jitterFraction > 1) {
            throw new IllegalArgumentException("jitterFraction must be between 0 and 1");
        }
        this.jitterFraction = jitterFraction;
        this.jitterSupplier = Objects.requireNonNull(jitterSupplier, "jitterSupplier");
    }

    public RetryDecision failure(
        int attempts,
        Duration retryAfter,
        FailureSeries previous,
        Throwable failure,
        Instant now
    ) {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(now, "now");
        String type = deepestCause(failure).getClass().getName();
        FailureSeries series = previous != null && previous.type().equals(type)
            ? previous
            : new FailureSeries(type, now);
        boolean fatal = !now.isBefore(series.startedAt().plus(fatalAfter));
        return new RetryDecision(fatal, fatal ? null : now.plus(delay(attempts, retryAfter)), series);
    }

    public Duration delay(int attempts, Duration retryAfter) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1");
        }
        if (retryAfter != null && retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must not be negative");
        }

        Duration exponentialDelay = baseDelay;
        for (int attempt = 1; attempt < attempts && exponentialDelay.compareTo(maxDelay) < 0; attempt++) {
            exponentialDelay = min(exponentialDelay.multipliedBy(2), maxDelay);
        }

        double jitter = jitterSupplier.getAsDouble();
        double factor = 1 - jitterFraction + 2 * jitterFraction * jitter;
        long jitteredNanos = Math.round(exponentialDelay.toNanos() * factor);
        Duration calculatedDelay = min(Duration.ofNanos(jitteredNanos), maxDelay);

        return retryAfter != null && retryAfter.compareTo(calculatedDelay) > 0 ? retryAfter : calculatedDelay;
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private Throwable deepestCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

}
