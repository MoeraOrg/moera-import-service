package org.moera.importer.source;

import java.time.Duration;

public class SourceTemporaryException extends RuntimeException {

    private final Duration retryAfter;

    public SourceTemporaryException(String message) {
        this(message, null, null);
    }

    public SourceTemporaryException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public SourceTemporaryException(String message, Duration retryAfter) {
        this(message, retryAfter, null);
    }

    public SourceTemporaryException(String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        if (retryAfter != null && retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must not be negative");
        }
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

}
