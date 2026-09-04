package org.moera.importer.model;

import java.util.Objects;

public record DestinationProfileState(
    String fingerprint,
    String sourceEmblemId,
    String avatarId,
    String avatarMediaId,
    String avatarShape
) {

    public DestinationProfileState {
        Objects.requireNonNull(fingerprint, "fingerprint");
    }

}
