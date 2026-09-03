package org.moera.importer.moera;

import java.nio.file.Path;
import java.util.Objects;

public record MoeraProfileUpdate(
    String fullName,
    String bioHtml,
    Path newEmblem,
    String emblemMimeType,
    String avatarId,
    String avatarMediaId,
    boolean hasEmblem
) {

    public MoeraProfileUpdate {
        Objects.requireNonNull(fullName, "fullName");
        Objects.requireNonNull(bioHtml, "bioHtml");
    }

}
