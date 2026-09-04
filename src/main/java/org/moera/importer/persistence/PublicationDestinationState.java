package org.moera.importer.persistence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record PublicationDestinationState(Boolean pinned, Map<String, String> storyIds) {

    public PublicationDestinationState {
        storyIds = Collections.unmodifiableMap(new LinkedHashMap<>(
            Objects.requireNonNull(storyIds, "storyIds")
        ));
    }

}
