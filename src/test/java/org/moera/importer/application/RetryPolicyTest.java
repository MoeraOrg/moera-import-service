package org.moera.importer.application;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");

    @Test
    void increasesDelayExponentiallyAndCapsIt() {
        RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(5), Duration.ofMinutes(15), 0);

        assertThat(policy.delay(1, null)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.delay(2, null)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.delay(20, null)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void retryAfterCannotShortenCalculatedDelay() {
        RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(5), Duration.ofMinutes(15), 0);

        assertThat(policy.delay(2, Duration.ofSeconds(3))).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.delay(2, Duration.ofMinutes(3))).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void appliesDeterministicSymmetricJitter() {
        RetryPolicy lowerPolicy = new RetryPolicy(Duration.ofSeconds(10), Duration.ofMinutes(15), .5, () -> 0);
        RetryPolicy upperPolicy = new RetryPolicy(Duration.ofSeconds(10), Duration.ofMinutes(15), .5, () -> 1);

        assertThat(lowerPolicy.delay(1, null)).isEqualTo(Duration.ofSeconds(5));
        assertThat(upperPolicy.delay(1, null)).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void retriesFirstFailureAndRecordsDeepestCauseType() {
        RetryPolicy policy = policy();

        RetryDecision decision = policy.failure(
            1, null, null, new TemporaryFailure(new AuthenticationFailure()), NOW
        );

        assertThat(decision.fatal()).isFalse();
        assertThat(decision.nextAttemptAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(decision.series()).isEqualTo(new FailureSeries(AuthenticationFailure.class.getName(), NOW));
    }

    @Test
    void makesSameTypeFailureFatalAtConfiguredDuration() {
        RetryPolicy policy = policy();
        FailureSeries series = new FailureSeries(AuthenticationFailure.class.getName(), NOW.minus(Duration.ofDays(1)));

        RetryDecision decision = policy.failure(
            4, null, series, new TemporaryFailure(new AuthenticationFailure()), NOW
        );

        assertThat(decision.fatal()).isTrue();
        assertThat(decision.nextAttemptAt()).isNull();
        assertThat(decision.series()).isEqualTo(series);
    }

    @Test
    void changingFailureTypeStartsNewSeries() {
        RetryPolicy policy = policy();
        FailureSeries previous = new FailureSeries(
            AuthenticationFailure.class.getName(), NOW.minus(Duration.ofDays(2))
        );

        RetryDecision decision = policy.failure(
            4, null, previous, new TemporaryFailure(new ConnectionFailure()), NOW
        );

        assertThat(decision.fatal()).isFalse();
        assertThat(decision.series()).isEqualTo(new FailureSeries(ConnectionFailure.class.getName(), NOW));
    }

    private RetryPolicy policy() {
        return new RetryPolicy(Duration.ofSeconds(5), Duration.ofMinutes(15), Duration.ofDays(1), 0, () -> 0.5);
    }

    private static class TemporaryFailure extends RuntimeException {

        TemporaryFailure(Throwable cause) {
            super(cause);
        }

    }

    private static class AuthenticationFailure extends RuntimeException {
    }

    private static class ConnectionFailure extends RuntimeException {
    }

}
