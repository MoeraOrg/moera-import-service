package org.moera.importer.source;

@FunctionalInterface
public interface SourceEventSink {

    void accept(SourceEvent event);

}
