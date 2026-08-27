package org.moera.importer.config;

import jakarta.inject.Inject;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("importReadinessHealthIndicator")
public class ReadinessHealthIndicator implements HealthIndicator {

    @Inject
    private StartupCoordinator startupCoordinator;

    @Override
    public Health health() {
        if (startupCoordinator.isReady()) {
            return Health.up().build();
        }
        return Health.down().withDetail("phase", startupCoordinator.phase().name()).build();
    }

}
