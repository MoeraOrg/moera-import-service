package org.moera.importer.moera;

import java.util.Objects;

public record UploadedMedia(String id, String hash) {

    public UploadedMedia {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(hash, "hash");
    }

}
