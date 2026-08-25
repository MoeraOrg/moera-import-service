package org.moera.importer.source;

import java.net.URI;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import org.moera.importer.model.AccessState;
import org.moera.importer.model.SubscriptionState;

public record ChannelResolution(
    String sourceId,
    URI sourceUri,
    JsonNode sourceData,
    AccessState accessState,
    JsonNode accessData,
    SubscriptionState subscriptionState,
    JsonNode subscriptionData
) {

    public ChannelResolution {
        Objects.requireNonNull(sourceUri, "sourceUri");
        Objects.requireNonNull(sourceData, "sourceData");
        Objects.requireNonNull(accessState, "accessState");
        Objects.requireNonNull(accessData, "accessData");
        Objects.requireNonNull(subscriptionState, "subscriptionState");
        Objects.requireNonNull(subscriptionData, "subscriptionData");
    }

}
