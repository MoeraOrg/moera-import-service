package org.moera.importer.application;

import java.util.Objects;

public record RenderedChannelProfile(String fullName, String bioHtml) {

    public RenderedChannelProfile {
        Objects.requireNonNull(fullName, "fullName");
        Objects.requireNonNull(bioHtml, "bioHtml");
    }

}
