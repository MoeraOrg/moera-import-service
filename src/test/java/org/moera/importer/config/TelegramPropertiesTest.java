package org.moera.importer.config;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramPropertiesTest {

    @Test
    void defaultsAreValid() {
        TelegramProperties properties = new TelegramProperties();

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsSettlingDelayShorterThanGroupingThreshold() {
        TelegramProperties properties = new TelegramProperties();
        properties.setGroupingThreshold(Duration.ofMinutes(5));
        properties.setSettlingDelay(Duration.ofSeconds(30));

        assertThatThrownBy(properties::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Telegram settling delay must be greater than or equal to grouping threshold");
    }

    @Test
    void acceptsSettlingDelayEqualToGroupingThreshold() {
        TelegramProperties properties = new TelegramProperties();
        properties.setGroupingThreshold(Duration.ofMinutes(5));
        properties.setSettlingDelay(Duration.ofMinutes(5));

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

}
