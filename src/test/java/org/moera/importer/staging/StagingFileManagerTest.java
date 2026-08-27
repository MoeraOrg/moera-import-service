package org.moera.importer.staging;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.moera.importer.config.WorkerProperties;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class StagingFileManagerTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void buildsDeterministicPathInsideStagingRoot() {
        StagingFileManager manager = manager();
        byte[] sourceHash = {0x00, 0x0f, (byte) 0xa5, (byte) 0xff};

        Path first = manager.path(17, 23, sourceHash);
        Path second = manager.path(17, 23, sourceHash);

        assertThat(first).isEqualTo(second);
        assertThat(first).isEqualTo(tempDirectory.toAbsolutePath().resolve("17/23-000fa5ff"));
        assertThat(first.getParent()).isDirectory();
    }

    @Test
    void sourceHashIsPartOfPathIdentity() {
        StagingFileManager manager = manager();

        Path first = manager.path(17, 23, new byte[] {1});
        Path second = manager.path(17, 23, new byte[] {2});

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void generatedPathCannotEscapeStagingRoot() {
        StagingFileManager manager = manager();

        Path path = manager.path(Long.MIN_VALUE, Long.MAX_VALUE, new byte[] {(byte) 0xff});

        assertThat(path.normalize().startsWith(tempDirectory.toAbsolutePath().normalize())).isTrue();
    }

    private StagingFileManager manager() {
        WorkerProperties workerProperties = new WorkerProperties();
        workerProperties.setStagingDirectory(tempDirectory);
        StagingFileManager manager = new StagingFileManager();
        ReflectionTestUtils.setField(manager, "workerProperties", workerProperties);
        return manager;
    }

}
