package org.moera.importer.moera;

import java.util.Map;
import java.util.Objects;

public record CreatedPosting(String postingId, Map<String, String> storyIds) {

    public CreatedPosting {
        Objects.requireNonNull(postingId, "postingId");
        storyIds = Map.copyOf(Objects.requireNonNull(storyIds, "storyIds"));
    }

}
