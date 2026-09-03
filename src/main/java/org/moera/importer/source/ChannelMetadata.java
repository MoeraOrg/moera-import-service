package org.moera.importer.source;

import java.util.List;
import java.util.Objects;

public record ChannelMetadata(
    String title,
    String description,
    List<DescriptionEntity> descriptionEntities,
    String publicUsername,
    Emblem emblem
) {

    public ChannelMetadata(String title, String description, String publicUsername, Emblem emblem) {
        this(title, description, List.of(), publicUsername, emblem);
    }

    public ChannelMetadata {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        descriptionEntities = List.copyOf(descriptionEntities);
    }

    public record DescriptionEntity(int offset, int length, DescriptionEntityType type) {

        public DescriptionEntity {
            if (offset < 0 || length <= 0) {
                throw new IllegalArgumentException("Description entity range must be positive");
            }
            Objects.requireNonNull(type, "type");
        }

    }

    public enum DescriptionEntityType {
        URL,
        EMAIL,
        MENTION
    }

    public record Emblem(String remoteId, String uniqueId, String mimeType) {

        public Emblem {
            Objects.requireNonNull(remoteId, "remoteId");
            Objects.requireNonNull(uniqueId, "uniqueId");
            Objects.requireNonNull(mimeType, "mimeType");
        }

    }

}
