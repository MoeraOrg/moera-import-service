package org.moera.importer.persistence;

import java.time.Instant;

public sealed interface DestinationCreateSlot {

    record Acquired() implements DestinationCreateSlot {
    }

    record Deferred(Instant retryAt) implements DestinationCreateSlot {
    }

}
