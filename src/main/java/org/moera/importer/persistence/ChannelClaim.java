package org.moera.importer.persistence;

import java.util.UUID;

import org.moera.importer.application.FailureSeries;
import org.moera.importer.model.ChannelOperation;
import org.moera.importer.source.ChannelSnapshot;

public record ChannelClaim(
    ChannelSnapshot channel,
    ChannelOperation operation,
    UUID processingToken,
    int attempts,
    FailureSeries failureSeries
) {

    public ChannelClaim(
        ChannelSnapshot channel,
        ChannelOperation operation,
        UUID processingToken,
        int attempts
    ) {
        this(channel, operation, processingToken, attempts, null);
    }

}
