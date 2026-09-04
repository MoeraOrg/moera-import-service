package org.moera.importer.source;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import org.moera.importer.model.AccessState;
import org.moera.importer.model.SourceType;
import org.moera.importer.model.SubscriptionState;

public record ChannelSnapshot(
    long id,
    SourceType sourceType,
    String sourceId,
    URI sourceUri,
    JsonNode sourceData,
    InitialImportMode initialImportMode,
    Instant initialImportAfter,
    ChannelMetadata metadata,
    AccessState accessState,
    JsonNode accessData,
    SubscriptionState subscriptionState,
    JsonNode subscriptionData,
    JsonNode waterline
) {

    public ChannelSnapshot(
        long id,
        SourceType sourceType,
        String sourceId,
        URI sourceUri,
        JsonNode sourceData,
        InitialImportMode initialImportMode,
        ChannelMetadata metadata,
        AccessState accessState,
        JsonNode accessData,
        SubscriptionState subscriptionState,
        JsonNode subscriptionData,
        JsonNode waterline
    ) {
        this(id, sourceType, sourceId, sourceUri, sourceData, initialImportMode, null, metadata, accessState,
            accessData, subscriptionState, subscriptionData, waterline);
    }

    public ChannelSnapshot(
        long id,
        SourceType sourceType,
        String sourceId,
        URI sourceUri,
        JsonNode sourceData,
        ChannelMetadata metadata,
        AccessState accessState,
        JsonNode accessData,
        SubscriptionState subscriptionState,
        JsonNode subscriptionData,
        JsonNode waterline
    ) {
        this(id, sourceType, sourceId, sourceUri, sourceData, InitialImportMode.RECENT, null, metadata, accessState,
            accessData, subscriptionState, subscriptionData, waterline);
    }

    public ChannelSnapshot(
        long id,
        SourceType sourceType,
        String sourceId,
        URI sourceUri,
        JsonNode sourceData,
        AccessState accessState,
        JsonNode accessData,
        SubscriptionState subscriptionState,
        JsonNode subscriptionData,
        JsonNode waterline
    ) {
        this(id, sourceType, sourceId, sourceUri, sourceData, InitialImportMode.RECENT, null, null, accessState,
            accessData, subscriptionState,
            subscriptionData, waterline);
    }

    public ChannelSnapshot {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceUri, "sourceUri");
        Objects.requireNonNull(sourceData, "sourceData");
        Objects.requireNonNull(initialImportMode, "initialImportMode");
        initialImportMode.validate(initialImportAfter);
        Objects.requireNonNull(accessState, "accessState");
        Objects.requireNonNull(accessData, "accessData");
        Objects.requireNonNull(subscriptionState, "subscriptionState");
        Objects.requireNonNull(subscriptionData, "subscriptionData");
    }

}
