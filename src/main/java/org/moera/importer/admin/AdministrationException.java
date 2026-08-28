package org.moera.importer.admin;

import org.springframework.http.HttpStatus;

public class AdministrationException extends RuntimeException {

    private final HttpStatus status;

    AdministrationException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

}
