package org.moera.importer.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class MoeraAuthenticationStartupTest {

    @Test
    void printsActionableFailureInsteadOfStackTrace(CapturedOutput output) {
        SpringApplication application = new SpringApplication(TestApplication.class);
        application.setBannerMode(Banner.Mode.OFF);
        application.setWebApplicationType(WebApplicationType.NONE);

        assertThatThrownBy(() -> application.run(
            "--moera.authentication.signing-key=bad",
            "--moera.authentication.addresses=127.0.0.1"
        )).hasRootCauseInstanceOf(MoeraAuthenticationConfigurationException.class);

        assertThat(output)
            .contains("APPLICATION FAILED TO START")
            .contains("MOERA_AUTHENTICATION_SIGNING_KEY must contain exactly 64 hexadecimal characters; got 3.")
            .contains("Set MOERA_AUTHENTICATION_SIGNING_KEY to a 32-byte private signing key encoded as hexadecimal.")
            .doesNotContain("Caused by:")
            .doesNotContain("MoeraAuthenticationProperties.java:");
    }

    @SpringBootConfiguration
    @EnableConfigurationProperties(MoeraAuthenticationProperties.class)
    static class TestApplication {

    }

}
