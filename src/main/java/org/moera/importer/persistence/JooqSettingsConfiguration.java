package org.moera.importer.persistence;

import org.jooq.impl.DefaultConfiguration;
import org.springframework.boot.jooq.autoconfigure.DefaultConfigurationCustomizer;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JooqSettingsConfiguration implements DefaultConfigurationCustomizer {

    @Override
    public void customize(DefaultConfiguration defaultConfiguration) {
        defaultConfiguration.settings().setRenderSchema(false);
    }

}
