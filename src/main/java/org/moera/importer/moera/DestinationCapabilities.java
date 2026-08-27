package org.moera.importer.moera;

import java.util.List;
import java.util.Objects;

import org.moera.lib.node.types.SourceFormat;

public record DestinationCapabilities(long mediaMaxSize, List<SourceFormat> sourceFormats) {

    public DestinationCapabilities {
        if (mediaMaxSize < 0) {
            throw new IllegalArgumentException("mediaMaxSize must not be negative");
        }
        sourceFormats = List.copyOf(Objects.requireNonNull(sourceFormats, "sourceFormats"));
    }

    public boolean supports(SourceFormat sourceFormat) {
        return sourceFormats.contains(sourceFormat);
    }

}
