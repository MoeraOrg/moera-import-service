package org.moera.importer.application;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

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

}
