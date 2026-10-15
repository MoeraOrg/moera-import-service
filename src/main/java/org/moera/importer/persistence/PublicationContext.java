package org.moera.importer.persistence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.moera.importer.model.Destination;
import org.moera.importer.model.Media;
import org.moera.importer.model.Message;

public record PublicationContext(
    long publicationId,
    int targetRevision,
    Destination destination,
    List<String> mirrorFeeds,
    String destinationPostId,
    Boolean destinationPinned,
    Map<String, String> destinationStoryIds,
    List<Member> members,
    List<DeliveredMedia> media,
    RepublishState republish
) {

    public PublicationContext(
        long publicationId,
        int targetRevision,
        Destination destination,
        List<String> mirrorFeeds,
        String destinationPostId,
        Boolean destinationPinned,
        Map<String, String> destinationStoryIds,
        List<Member> members,
        List<DeliveredMedia> media
    ) {
        this(publicationId, targetRevision, destination, mirrorFeeds, destinationPostId, destinationPinned,
            destinationStoryIds, members, media, null);
    }

    public PublicationContext {
        Objects.requireNonNull(destination, "destination");
        mirrorFeeds = List.copyOf(Objects.requireNonNull(mirrorFeeds, "mirrorFeeds"));
        destinationStoryIds = Collections.unmodifiableMap(new LinkedHashMap<>(
            Objects.requireNonNull(destinationStoryIds, "destinationStoryIds")
        ));
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
