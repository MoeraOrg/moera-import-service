package org.moera.importer.application;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

public final class RetryPolicy {

    private final Duration baseDelay;
    private final Duration maxDelay;
    private final double jitterFraction;
    private final DoubleSupplier jitterSupplier;

    public RetryPolicy(Duration baseDelay, Duration maxDelay, double jitterFraction) {
        this(baseDelay, maxDelay, jitterFraction, ThreadLocalRandom.current()::nextDouble);
    }

    RetryPolicy(
        Duration baseDelay,
        Duration maxDelay,
        double jitterFraction,
        DoubleSupplier jitterSupplier
    ) {
        this.baseDelay = requirePositive(baseDelay, "baseDelay");
        this.maxDelay = requirePositive(maxDelay, "maxDelay");
        if (this.maxDelay.compareTo(this.baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must not be shorter than baseDelay");
        }
        if (!Double.isFinite(jitterFraction) || jitterFraction < 0 || jitterFraction > 1) {
            throw new IllegalArgumentException("jitterFraction must be between 0 and 1");
        }
        this.jitterFraction = jitterFraction;
        this.jitterSupplier = Objects.requireNonNull(jitterSupplier, "jitterSupplier");
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

}
