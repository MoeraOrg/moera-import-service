package org.moera.importer.source;

public class SourcePermanentException extends RuntimeException {

    public SourcePermanentException(String message) {
        super(message);
    }

    public SourcePermanentException(String message, Throwable cause) {
        super(message, cause);
    }

}
