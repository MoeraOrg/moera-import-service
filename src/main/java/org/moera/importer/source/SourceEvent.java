package org.moera.importer.source;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import org.moera.importer.model.SourceType;

public sealed interface SourceEvent {

    record MessageChanged(SourceType sourceType, String channelSourceId, SourceMessage message)
        implements SourceEvent {

        public MessageChanged {
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(channelSourceId, "channelSourceId");
            Objects.requireNonNull(message, "message");
        }

    }

    record MessageDeleted(SourceType sourceType, String channelSourceId, String messageSourceId)
        implements SourceEvent {

        public MessageDeleted {
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(channelSourceId, "channelSourceId");
            Objects.requireNonNull(messageSourceId, "messageSourceId");
        }

    }

    record ChannelMetadataChanged(
        SourceType sourceType,
        String channelSourceId,
        ChannelMetadata metadata
    ) implements SourceEvent {

        public ChannelMetadataChanged {
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(channelSourceId, "channelSourceId");
            Objects.requireNonNull(metadata, "metadata");
        }

    }

    record AccessLost(SourceType sourceType, String channelSourceId, JsonNode data) implements SourceEvent {

        public AccessLost {
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(channelSourceId, "channelSourceId");
            Objects.requireNonNull(data, "data");
        }

    }

    record SubscriptionLost(SourceType sourceType, String channelSourceId, JsonNode data) implements SourceEvent {

        public SubscriptionLost {
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(channelSourceId, "channelSourceId");
            Objects.requireNonNull(data, "data");
        }

    }

}
