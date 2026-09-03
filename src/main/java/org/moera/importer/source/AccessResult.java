package org.moera.importer.source;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import org.moera.importer.model.AccessState;

public record AccessResult(AccessState state, String resolvedSourceId, JsonNode data, ChannelMetadata metadata) {

    public AccessResult(AccessState state, String resolvedSourceId, JsonNode data) {
        this(state, resolvedSourceId, data, null);
    }

    public AccessResult {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(data, "data");
    }

}
