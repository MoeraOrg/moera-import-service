package org.moera.importer.source;

import java.util.Objects;

public record ChannelMetadata(
    String title,
    String descriptionHtml,
    String publicUsername,
    Emblem emblem
) {

    public ChannelMetadata {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(descriptionHtml, "descriptionHtml");
    }

    public record Emblem(String remoteId, String uniqueId, String mimeType) {

        public Emblem {
            Objects.requireNonNull(remoteId, "remoteId");
            Objects.requireNonNull(uniqueId, "uniqueId");
            Objects.requireNonNull(mimeType, "mimeType");
        }

    }

}
