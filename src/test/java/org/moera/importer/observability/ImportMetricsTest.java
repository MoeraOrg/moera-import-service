package org.moera.importer.observability;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.moera.importer.model.ChannelOperation;
import org.moera.importer.model.SourceType;
import org.moera.importer.source.telegram.TelegramSkipReason;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class ImportMetricsTest {

    @Test
    void recordsSourceOperationsUsingOnlyStableCategoricalLabels() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ImportMetrics importMetrics = metrics(meterRegistry);

        Timer.Sample sample = importMetrics.startTimer();
        importMetrics.sourceOperation(SourceType.TELEGRAM, ChannelOperation.POLL, "success", sample);

        assertThat(meterRegistry.get("source_operations_total").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("source_operation_duration")
            .tags("source", "telegram", "operation", "poll", "result", "success")
            .timer().count()).isEqualTo(1);
    }

    @Test
    void recordsUnknownPublicationOutcomeAndTelegramSkipSeparately() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ImportMetrics importMetrics = metrics(meterRegistry);

        importMetrics.publicationUnknownOutcome();
        importMetrics.telegramMessageSkipped("service_message", TelegramSkipReason.SERVICE_MESSAGE);

        assertThat(meterRegistry.get("publication_unknown_outcomes_total").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("telegram_messages_skipped_total")
            .tags("content_family", "service_message", "reason", "service_message")
            .counter().count()).isEqualTo(1);
    }

    private ImportMetrics metrics(SimpleMeterRegistry meterRegistry) {
        ImportMetrics importMetrics = new ImportMetrics();
        ReflectionTestUtils.setField(importMetrics, "meterRegistry", meterRegistry);
        return importMetrics;
    }

}
