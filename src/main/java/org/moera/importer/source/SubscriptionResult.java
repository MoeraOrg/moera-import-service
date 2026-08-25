package org.moera.importer.source;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import org.moera.importer.model.SubscriptionState;

public record SubscriptionResult(SubscriptionState state, JsonNode data) {

    public SubscriptionResult {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(data, "data");
    }

}
