package org.moera.importer.config;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class MoeraAuthenticationFailureAnalyzerTest {

    private static final String VALID_KEY = "0".repeat(63) + "1";

    @Test
    void explainsInvalidSigningKeyWithoutExposingIt() {
        String secret = "do-not-print-this";
        MoeraAuthenticationProperties properties = new MoeraAuthenticationProperties();
        properties.setSigningKey(secret);
        properties.setAddresses(List.of("127.0.0.1"));
        Throwable failure = catchThrowable(properties::validate);

        FailureAnalysis analysis = new MoeraAuthenticationFailureAnalyzer()
            .analyze(new IllegalStateException("Bean creation failed", failure));

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription())
            .isEqualTo("Invalid Moera authentication configuration:\n"
                + "MOERA_AUTHENTICATION_SIGNING_KEY must contain exactly 64 hexadecimal characters; got 17.");
        assertThat(analysis.getAction())
            .isEqualTo("Set MOERA_AUTHENTICATION_SIGNING_KEY to a 32-byte private signing key encoded as hexadecimal.");
        assertThat(analysis.getDescription()).doesNotContain(secret);
        assertThat(analysis.getAction()).doesNotContain(secret);
    }

    @Test
    void explainsMissingAddresses() {
        MoeraAuthenticationProperties properties = new MoeraAuthenticationProperties();
        properties.setSigningKey(VALID_KEY);
        Throwable failure = catchThrowable(properties::validate);

        FailureAnalysis analysis = new MoeraAuthenticationFailureAnalyzer().analyze(failure);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription())
            .isEqualTo("Invalid Moera authentication configuration:\n"
                + "MOERA_AUTHENTICATION_ADDRESSES must contain at least one IP address or hostname.");
        assertThat(analysis.getAction())
            .isEqualTo("Set MOERA_AUTHENTICATION_ADDRESSES to one or more comma-separated public IP addresses "
                + "or hostnames.");
    }

    @Test
    void explainsInvalidName() {
        MoeraAuthenticationProperties properties = new MoeraAuthenticationProperties();
        properties.setName("bad_name_extra");
        properties.setSigningKey(VALID_KEY);
        properties.setAddresses(List.of("127.0.0.1"));
        Throwable failure = catchThrowable(properties::validate);

        FailureAnalysis analysis = new MoeraAuthenticationFailureAnalyzer().analyze(failure);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription())
            .isEqualTo("Invalid Moera authentication configuration:\n"
                + "MOERA_AUTHENTICATION_NAME must be a valid Moera node name.");
        assertThat(analysis.getAction())
            .isEqualTo("Set MOERA_AUTHENTICATION_NAME to the registered importer name with its generation suffix "
                + "(for example, importer_0).");
    }

}
