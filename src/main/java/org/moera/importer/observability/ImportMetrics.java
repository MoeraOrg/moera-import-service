package org.moera.importer.observability;

import java.util.Locale;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.moera.importer.model.ChannelOperation;
import org.moera.importer.model.MediaSkipReason;
import org.moera.importer.model.SourceType;
import org.moera.importer.source.telegram.TelegramSkipReason;
import org.springframework.stereotype.Component;

@Component
public class ImportMetrics {

    @Inject
    private MeterRegistry meterRegistry;

    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void sourceOperation(
        SourceType sourceType,
        ChannelOperation operation,
        String result,
        Timer.Sample sample
    ) {
        String source = label(sourceType);
        String operationLabel = label(operation);
        Counter.builder("source_operations_total")
            .tag("source", source)
            .tag("operation", operationLabel)
            .tag("result", result)
            .register(meterRegistry)
            .increment();
        sample.stop(Timer.builder("source_operation_duration")
            .tag("source", source)
            .tag("operation", operationLabel)
            .tag("result", result)
            .register(meterRegistry));
    }

    public void publicationCompleted(String operation) {
        meterRegistry.counter("publications_" + operation + "_total").increment();
    }

    public void publicationFailure(String result) {
        meterRegistry.counter("publication_failures_total", "result", result).increment();
    }

    public void publicationUnknownOutcome() {
        meterRegistry.counter("publication_unknown_outcomes_total").increment();
    }

    public void mediaUploaded() {
        meterRegistry.counter("media_uploaded_total").increment();
    }

    public void mediaSkipped(MediaSkipReason reason) {
        meterRegistry.counter("media_skipped_total", "reason", label(reason)).increment();
    }

    public void mediaUploadBytes(long bytes) {
        DistributionSummary.builder("media_upload_bytes")
            .baseUnit("bytes")
            .register(meterRegistry)
            .record(bytes);
    }

    public void pollMessagesFound(int count) {
        meterRegistry.counter("poll_messages_found_total").increment(count);
    }

    public void telegramMessageSkipped(String contentFamily, TelegramSkipReason reason) {
        meterRegistry.counter(
            "telegram_messages_skipped_total",
            "content_family", contentFamily,
            "reason", label(reason)
        ).increment();
    }

    public void messageRevision(String result) {
        meterRegistry.counter("message_revisions_total", "result", result).increment();
    }

    public void leaseReclaimed(String queue, int count) {
        if (count > 0) {
            meterRegistry.counter("lease_reclaims_total", "queue", queue).increment(count);
        }
    }

    private String label(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

}
