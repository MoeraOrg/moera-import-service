package org.moera.importer.moera;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

public record MoeraProfileUpdate(
    String fullName,
    String bioHtml,
    URI sourceUri,
    String viaEmail,
    Path newEmblem,
    String emblemMimeType,
    String avatarId,
    String avatarMediaId,
    boolean hasEmblem
) {

    public MoeraProfileUpdate {
        Objects.requireNonNull(fullName, "fullName");
        Objects.requireNonNull(bioHtml, "bioHtml");
        Objects.requireNonNull(sourceUri, "sourceUri");
        Objects.requireNonNull(viaEmail, "viaEmail");
    }

}
