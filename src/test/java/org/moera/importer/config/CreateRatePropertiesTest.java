package org.moera.importer.config;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateRatePropertiesTest {

    @Test
    void defaultsToTenCreatesPerMinute() {
        CreateRateProperties properties = new CreateRateProperties();

        assertThat(properties.getCount()).isEqualTo(10);
        assertThat(properties.getPeriod()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.interval()).isEqualTo(Duration.ofSeconds(6));
    }

    @Test
    void roundsIntervalUpWhenPeriodIsNotEvenlyDivisible() {
        CreateRateProperties properties = new CreateRateProperties();
        properties.setCount(3);
        properties.setPeriod(Duration.ofNanos(10));

        assertThat(properties.interval()).isEqualTo(Duration.ofNanos(4));
    }

    @Test
    void rejectsNonPositiveCount() {
        CreateRateProperties properties = new CreateRateProperties();
        properties.setCount(0);

        assertThatThrownBy(properties::validate)
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingOrNonPositivePeriod() {
        CreateRateProperties missing = new CreateRateProperties();
        missing.setPeriod(null);
        CreateRateProperties zero = new CreateRateProperties();
        zero.setPeriod(Duration.ZERO);

        assertThatThrownBy(missing::validate)
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(zero::validate)
            .isInstanceOf(IllegalStateException.class);
    }

}
