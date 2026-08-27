package org.moera.importer.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.moera.importer.persistence.BacklogRepository;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class BacklogMetricsTest {

    @Test
    void exposesOldestOverdueAgeForEachDurableWorkQueue() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        BacklogMetrics backlogMetrics = new BacklogMetrics();
        ReflectionTestUtils.setField(backlogMetrics, "backlogRepository", new FixedBacklogRepository());
        ReflectionTestUtils.setField(backlogMetrics, "meterRegistry", meterRegistry);

        backlogMetrics.initialize();

        assertThat(age(meterRegistry, "channel")).isEqualTo(11);
        assertThat(age(meterRegistry, "publication_media")).isEqualTo(22);
        assertThat(age(meterRegistry, "publication")).isEqualTo(33);
    }

    private double age(SimpleMeterRegistry meterRegistry, String queue) {
        return meterRegistry.get("overdue_work_age_seconds").tag("queue", queue).gauge().value();
    }

    private static class FixedBacklogRepository extends BacklogRepository {

        @Override
        public double channelOverdueAgeSeconds() {
            return 11;
        }

        @Override
        public double publicationMediaOverdueAgeSeconds() {
            return 22;
        }

        @Override
        public double publicationOverdueAgeSeconds() {
            return 33;
        }

    }

}
