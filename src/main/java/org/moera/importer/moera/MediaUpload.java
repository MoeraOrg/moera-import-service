package org.moera.importer.moera;

import java.util.Objects;

public sealed interface MediaUpload {

    record Ready(UploadedMedia media) implements MediaUpload {

        public Ready {
            Objects.requireNonNull(media, "media");
        }

    }

    record AwaitingCompression(UploadedMedia original) implements MediaUpload {

        public AwaitingCompression {
            Objects.requireNonNull(original, "original");
        }

    }

}
