package org.moera.importer.moera;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import jakarta.inject.Inject;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class MoeraCredentialStore {

    @Inject
    private Environment environment;

    public String resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new MoeraConfigurationException("Moera credential reference is missing");
        }
        String secret;
        if (reference.startsWith("env:")) {
            secret = environment.getProperty(reference.substring("env:".length()));
        } else if (reference.startsWith("file:")) {
            secret = readSecret(Path.of(reference.substring("file:".length())));
        } else {
            throw new MoeraConfigurationException("Unsupported Moera credential reference");
        }
        if (secret == null || secret.isBlank()) {
            throw new MoeraConfigurationException("Moera credential is unavailable");
        }
        return secret.strip();
    }

    private String readSecret(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new MoeraConfigurationException("Cannot read Moera credential file", e);
        }
    }

}
