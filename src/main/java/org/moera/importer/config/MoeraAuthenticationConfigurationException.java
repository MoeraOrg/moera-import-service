package org.moera.importer.config;

public class MoeraAuthenticationConfigurationException extends IllegalStateException {

    private final String action;

    MoeraAuthenticationConfigurationException(String message, String action) {
        super(message);
        this.action = action;
    }

    MoeraAuthenticationConfigurationException(String message, String action, Throwable cause) {
        super(message, cause);
        this.action = action;
    }

    String getAction() {
        return action;
    }

}
