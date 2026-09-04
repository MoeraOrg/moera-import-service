package org.moera.importer.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

public class MoeraAuthenticationFailureAnalyzer
        extends AbstractFailureAnalyzer<MoeraAuthenticationConfigurationException> {

    @Override
    protected FailureAnalysis analyze(
        Throwable rootFailure,
        MoeraAuthenticationConfigurationException cause
    ) {
        return new FailureAnalysis(
            "Invalid Moera authentication configuration:\n" + cause.getMessage(),
            cause.getAction(),
            cause
        );
    }

}
