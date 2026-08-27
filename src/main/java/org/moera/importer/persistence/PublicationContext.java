package org.moera.importer.persistence;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import org.moera.importer.model.Destination;
import org.moera.importer.model.Media;
import org.moera.importer.model.Message;

public record PublicationContext(
    long publicationId,
    int targetRevision,
    Destination destination,
    JsonNode mirrorConfig,
    String destinationPostId,
    JsonNode destinationData,
    List<Member> members,
    List<DeliveredMedia> media
) {

    public PublicationContext {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(mirrorConfig, "mirrorConfig");
        Objects.requireNonNull(destinationData, "destinationData");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        media = List.copyOf(Objects.requireNonNull(media, "media"));
    }

    public record Member(Message message, List<MessageLinkPreview> linkPreviews) {

        public Member {
            Objects.requireNonNull(message, "message");
            linkPreviews = List.copyOf(Objects.requireNonNull(linkPreviews, "linkPreviews"));
        }

    }

    public record DeliveredMedia(Media media, String destinationMediaId, String destinationMediaHash) {

        public DeliveredMedia {
            Objects.requireNonNull(media, "media");
            Objects.requireNonNull(destinationMediaId, "destinationMediaId");
            Objects.requireNonNull(destinationMediaHash, "destinationMediaHash");
        }

    }

}
