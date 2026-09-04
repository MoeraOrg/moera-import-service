package org.moera.importer.moera;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record CreatedPosting(String postingId, Map<String, String> storyIds) {

    public CreatedPosting {
        Objects.requireNonNull(postingId, "postingId");
        storyIds = Collections.unmodifiableMap(new LinkedHashMap<>(
            Objects.requireNonNull(storyIds, "storyIds")
        ));
    }

}
