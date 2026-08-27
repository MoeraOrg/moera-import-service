package org.moera.importer.staging;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Objects;
import jakarta.inject.Inject;

import org.moera.importer.config.StorageProperties;
import org.springframework.stereotype.Component;

@Component
public class StagingFileManager {

    @Inject
    private StorageProperties storageProperties;

    public Path path(long publicationId, long mediaId, byte[] sourceHash) {
        Objects.requireNonNull(sourceHash, "sourceHash");
        Path root = storageProperties.getStagingDirectory().toAbsolutePath().normalize();
        String fileName = mediaId + "-" + HexFormat.of().formatHex(sourceHash);
        Path path = root.resolve(Long.toString(publicationId)).resolve(fileName).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Generated staging path is outside the staging directory");
        }
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create staging directory", e);
        }
        return path;
    }

}
