package org.moera.importer.persistence;

import java.util.Objects;

import org.moera.importer.model.Destination;
import org.moera.importer.model.Media;
import org.moera.importer.source.ChannelSnapshot;

public record PublicationMediaContext(
    long publicationId,
    Destination destination,
    ChannelSnapshot channel,
    Media media,
    String destinationMediaId,
    String destinationHash,
    boolean compressionPending
) {

    public PublicationMediaContext {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(media, "media");
    }

}
