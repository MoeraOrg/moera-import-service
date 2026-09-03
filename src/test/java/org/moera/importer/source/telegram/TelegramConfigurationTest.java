package org.moera.importer.source.telegram;

import java.util.Map;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.moera.importer.config.TelegramProperties;
import org.moera.importer.observability.ImportMetrics;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramConfigurationTest {

    @Test
    void exposesTelegramClientAsForwardMetadataResolver() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("test", Map.of("moera.telegram.enabled", "true"))
            );
            context.registerBean(TelegramProperties.class, TelegramProperties::new);
            context.registerBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new);
            context.registerBean(ImportMetrics.class, ImportMetrics::new);
            context.register(TelegramConfiguration.class, TelegramMessageMapper.class);
            context.refresh();

            assertThat(context.getBean(TelegramForwardMetadataResolver.class))
                .isSameAs(context.getBean(TelegramClient.class));
        }
    }

}
