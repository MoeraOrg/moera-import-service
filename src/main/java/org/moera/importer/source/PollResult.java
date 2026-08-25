package org.moera.importer.source;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

public record PollResult(List<SourceMessage> messages, JsonNode waterline) {

    public PollResult {
        messages = List.copyOf(messages);
    }

}
