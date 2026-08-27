package org.moera.importer.observability;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.moera.importer.persistence.BacklogRepository;
import org.springframework.stereotype.Component;

@Component
public class BacklogMetrics {

    @Inject
    private BacklogRepository backlogRepository;

    @Inject
    private MeterRegistry meterRegistry;

    @PostConstruct
    public void initialize() {
        gauge("channel", BacklogRepository::channelOverdueAgeSeconds);
        gauge("publication_media", BacklogRepository::publicationMediaOverdueAgeSeconds);
        gauge("publication", BacklogRepository::publicationOverdueAgeSeconds);
    }

    private void gauge(String queue, java.util.function.ToDoubleFunction<BacklogRepository> value) {
        Gauge.builder("overdue_work_age_seconds", backlogRepository, value)
            .baseUnit("seconds")
            .tag("queue", queue)
            .register(meterRegistry);
    }

}
