package org.moera.importer.admin;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.moera.importer.model.AccessState;
import org.moera.importer.model.SourceType;
import org.moera.importer.model.SubscriptionState;

public final class AdministrationModels {

    private AdministrationModels() {
    }

    public record ChannelCreate(
        SourceType sourceType,
        String sourceUri,
        boolean fullHistory,
        Instant initialImportAfter
    ) {

        @JsonCreator
        public ChannelCreate(
            @JsonProperty("sourceType") SourceType sourceType,
            @JsonProperty("sourceUri") String sourceUri,
            @JsonProperty("fullHistory") Boolean fullHistory,
            @JsonProperty("initialImportAfter") Instant initialImportAfter
        ) {
            this(sourceType, sourceUri, Boolean.TRUE.equals(fullHistory), initialImportAfter);
        }

        public ChannelCreate(SourceType sourceType, String sourceUri, boolean fullHistory) {
            this(sourceType, sourceUri, fullHistory, null);
        }

    }

    public record ChannelView(
        long id,
        SourceType sourceType,
        String sourceId,
        String sourceUri,
        AccessState accessState,
        SubscriptionState subscriptionState
    ) {
    }

    public record DestinationCreate(String nodeName, boolean profileSync) {

        @JsonCreator
        public DestinationCreate(
            @JsonProperty("nodeName") String nodeName,
            @JsonProperty("profileSync") Boolean profileSync
        ) {
            this(nodeName, profileSync == null || profileSync);
        }

        public DestinationCreate(String nodeName) {
            this(nodeName, true);
        }

    }

    public record DestinationView(long id, String nodeName, boolean profileSync) {
    }

    public record MirrorCreate(long channelId, long destinationId, List<String> feeds) {
    }

    public record MirrorView(long id, long channelId, long destinationId, List<String> feeds) {
    }

}
