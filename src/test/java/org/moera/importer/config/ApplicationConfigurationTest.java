package org.moera.importer.config;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationConfigurationTest {

    @Test
    void telegramSwitchesUseTelegramEnvironmentVariables() throws IOException {
        MutablePropertySources propertySources = new MutablePropertySources();
        propertySources.addFirst(new MapPropertySource("environment", Map.of(
            "TELEGRAM_ENABLED", "true",
            "TELEGRAM_LOGIN", "true"
        )));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
            .forEach(propertySources::addLast);
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(propertySources);

        assertThat(resolver.getProperty("moera.telegram.enabled", Boolean.class)).isTrue();
        assertThat(resolver.getProperty("moera.telegram.login", Boolean.class)).isTrue();
    }

    @Test
    void moeraNamingServerUsesEnvironmentVariable() throws IOException {
        MutablePropertySources propertySources = new MutablePropertySources();
        propertySources.addFirst(new MapPropertySource("environment", Map.of(
            "MOERA_NAMING_SERVER", "development"
        )));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
            .forEach(propertySources::addLast);
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(propertySources);

        assertThat(resolver.getProperty("moera.naming-server")).isEqualTo("development");
    }

    @Test
    void fileLoggingUsesRollingFileWithEnvironmentOverride() throws IOException {
        MutablePropertySources propertySources = new MutablePropertySources();
        propertySources.addFirst(new MapPropertySource("environment", Map.of(
            "LOG_FILE", "/var/log/moera/import.log"
        )));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
            .forEach(propertySources::addLast);
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(propertySources);

        assertThat(resolver.getProperty("logging.file.name")).isEqualTo("/var/log/moera/import.log");
        assertThat(resolver.getProperty("logging.logback.rollingpolicy.max-file-size")).isEqualTo("100MB");
        assertThat(resolver.getProperty("logging.logback.rollingpolicy.max-history", Integer.class)).isEqualTo(7);
        assertThat(resolver.getProperty("logging.logback.rollingpolicy.total-size-cap")).isEqualTo("0B");
        assertThat(resolver.getProperty("logging.logback.rollingpolicy.clean-history-on-start", Boolean.class))
            .isFalse();
        assertThat(resolver.getProperty("logging.level.it.tdlight")).isEqualTo("WARN");
    }

}
