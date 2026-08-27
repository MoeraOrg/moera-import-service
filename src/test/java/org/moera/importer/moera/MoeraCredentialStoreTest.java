package org.moera.importer.moera;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoeraCredentialStoreTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void resolvesEnvironmentReference() {
        MoeraCredentialStore store = store(new MockEnvironment().withProperty("MOERA_IMPORT_TOKEN", "secret"));

        assertThat(store.resolve("env:MOERA_IMPORT_TOKEN")).isEqualTo("secret");
    }

    @Test
    void resolvesFileReferenceAndTrimsLineEnding() throws Exception {
        Path secret = temporaryDirectory.resolve("token");
        Files.writeString(secret, "secret\n");

        assertThat(store(new MockEnvironment()).resolve("file:" + secret)).isEqualTo("secret");
    }

    @Test
    void rejectsInlineCredentialsAndMissingSecrets() {
        MoeraCredentialStore store = store(new MockEnvironment());

        assertThatThrownBy(() -> store.resolve("secret"))
            .isInstanceOf(MoeraConfigurationException.class);
        assertThatThrownBy(() -> store.resolve("env:MISSING"))
            .isInstanceOf(MoeraConfigurationException.class);
    }

    private MoeraCredentialStore store(MockEnvironment environment) {
        MoeraCredentialStore store = new MoeraCredentialStore();
        ReflectionTestUtils.setField(store, "environment", environment);
        return store;
    }

}
