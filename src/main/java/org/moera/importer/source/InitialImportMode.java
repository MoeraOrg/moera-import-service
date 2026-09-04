package org.moera.importer.source;

import java.time.Instant;

public enum InitialImportMode {

    RECENT,
    FULL,
    AFTER;

    public void validate(Instant initialImportAfter) {
        if ((this == AFTER) != (initialImportAfter != null)) {
            throw new IllegalArgumentException("Initial import mode and timestamp do not match");
        }
    }

}
