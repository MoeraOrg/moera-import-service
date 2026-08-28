package org.moera.importer.admin;

import java.util.List;

import org.moera.importer.model.AccessState;
import org.moera.importer.model.SourceType;
import org.moera.importer.model.SubscriptionState;

public final class AdministrationModels {

    private AdministrationModels() {
    }

    public record ChannelCreate(SourceType sourceType, String sourceUri) {
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

    public record DestinationCreate(String nodeName, String credentialRef) {
    }

    public record DestinationView(long id, String nodeName, String credentialRef) {
    }

    public record MirrorCreate(long channelId, long destinationId, List<String> feeds) {
    }

    public record MirrorView(long id, long channelId, long destinationId, List<String> feeds) {
    }

}
