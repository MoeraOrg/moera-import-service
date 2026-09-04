package org.moera.importer.source;

import java.util.Objects;

public record ForwardAttribution(String name, String uri) {

    public ForwardAttribution {
        Objects.requireNonNull(name, "name");
    }

}
