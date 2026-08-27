package org.moera.importer.source.telegram;

import org.moera.importer.source.SourceMessage;

public sealed interface TelegramMappingResult {

    record Mapped(SourceMessage message) implements TelegramMappingResult {
    }

    record Skipped(TelegramSkipReason reason) implements TelegramMappingResult {
    }

}
