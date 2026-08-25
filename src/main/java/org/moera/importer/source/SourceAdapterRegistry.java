package org.moera.importer.source;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

import org.moera.importer.model.SourceType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class SourceAdapterRegistry {

    @Inject
    private ObjectProvider<SourceAdapter> objectProvider;

    private Map<SourceType, SourceAdapter> adapters;

    @PostConstruct
    public void initialize() {
        EnumMap<SourceType, SourceAdapter> adaptersByType = new EnumMap<>(SourceType.class);
        for (SourceAdapter adapter : objectProvider) {
            Objects.requireNonNull(adapter, "adapter");
            SourceType sourceType = Objects.requireNonNull(adapter.type(), "adapter.type()");
            SourceAdapter previous = adaptersByType.putIfAbsent(sourceType, adapter);
            if (previous != null) {
                throw new IllegalStateException("Duplicate source adapter registration for " + sourceType);
            }
        }
        this.adapters = Map.copyOf(adaptersByType);
    }

    public SourceAdapter get(SourceType sourceType) {
        Objects.requireNonNull(sourceType, "sourceType");
        SourceAdapter adapter = adapters.get(sourceType);
        if (adapter == null) {
            throw new IllegalStateException("No source adapter registered for " + sourceType);
        }
        return adapter;
    }

}
