package org.moera.importer.application;

import java.util.List;
import java.util.Objects;

import org.moera.lib.node.types.MediaToAttach;
import org.moera.lib.node.types.SourceFormat;
import org.moera.lib.node.types.StoryAttributes;
import org.moera.lib.node.types.body.Body;

public record PublicationPayload(
    Body bodySrc,
    SourceFormat bodySrcFormat,
    List<MediaToAttach> media,
    List<StoryAttributes> publications,
    boolean pinned
) {

    public PublicationPayload {
        Objects.requireNonNull(bodySrc, "bodySrc");
        Objects.requireNonNull(bodySrcFormat, "bodySrcFormat");
        media = List.copyOf(Objects.requireNonNull(media, "media"));
        publications = List.copyOf(Objects.requireNonNull(publications, "publications"));
    }

    public boolean empty() {
        return (bodySrc.getText() == null || bodySrc.getText().isBlank())
            && bodySrc.getLinkPreviews().isEmpty()
            && media.isEmpty();
    }

}
