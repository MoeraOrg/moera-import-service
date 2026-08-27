package org.moera.importer.staging;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.moera.importer.config.StorageProperties;
import org.moera.importer.persistence.PublicationMediaRepository;
import org.moera.importer.persistence.StagingMediaKey;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class StagingCleanupTest {

    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");
    private static final String HASH_ONE = "01".repeat(32);
    private static final String HASH_TWO = "02".repeat(32);

    @TempDir
    private Path temporaryDirectory;

    @Test
    void retainsOnlyFreshFilesAndCurrentMediaStillWaitingForUpload() throws Exception {
        Path uploadPending = oldFile(11, 101, HASH_ONE);
        Path compressionPending = oldFile(12, 102, HASH_ONE);
        Path uploaded = oldFile(13, 103, HASH_ONE);
        Path skipped = oldFile(14, 104, HASH_ONE);
        Path deletedRow = oldFile(15, 105, HASH_ONE);
        Path changedHash = oldFile(16, 106, HASH_ONE);
        Path orphan = oldFile(17, 107, HASH_ONE);
        Path freshOrphan = file(18, 108, HASH_TWO, NOW.minus(Duration.ofMinutes(5)));

        RecordingPublicationMediaRepository repository = new RecordingPublicationMediaRepository(Set.of(
            new StagingMediaKey(11, 101, HASH_ONE)
        ));
        StagingCleanup stagingCleanup = cleanup(repository, 100);

        stagingCleanup.clean();

        assertThat(uploadPending).exists();
        assertThat(freshOrphan).exists();
        assertThat(List.of(compressionPending, uploaded, skipped, deletedRow, changedHash, orphan))
            .allMatch(Files::notExists);
        assertThat(temporaryDirectory.resolve("12")).doesNotExist();
    }

    @Test
    void checksOldFilesAgainstDatabaseInConfiguredBatches() throws Exception {
        for (int index = 1; index <= 5; index++) {
            oldFile(index, index * 10L, HASH_ONE);
        }
        RecordingPublicationMediaRepository repository = new RecordingPublicationMediaRepository(Set.of());
        StagingCleanup stagingCleanup = cleanup(repository, 2);

        stagingCleanup.clean();

        assertThat(repository.batches).hasSize(3);
        assertThat(repository.batches).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(2));
        assertThat(Files.walk(temporaryDirectory).filter(Files::isRegularFile)).isEmpty();
    }

    private StagingCleanup cleanup(RecordingPublicationMediaRepository repository, int batchSize) {
        StorageProperties storageProperties = new StorageProperties();
        storageProperties.setStagingDirectory(temporaryDirectory);
        storageProperties.setStagingGracePeriod(Duration.ofHours(1));
        storageProperties.setCleanupBatchSize(batchSize);
        StagingCleanup stagingCleanup = new StagingCleanup();
        ReflectionTestUtils.setField(stagingCleanup, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(stagingCleanup, "publicationMediaRepository", repository);
        ReflectionTestUtils.setField(stagingCleanup, "storageProperties", storageProperties);
        return stagingCleanup;
    }

    private Path oldFile(long publicationId, long mediaId, String hash) throws Exception {
        return file(publicationId, mediaId, hash, NOW.minus(Duration.ofHours(2)));
    }

    private Path file(long publicationId, long mediaId, String hash, Instant modifiedAt) throws Exception {
        Path file = temporaryDirectory.resolve(Long.toString(publicationId)).resolve(mediaId + "-" + hash);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "data");
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt));
        return file;
    }

    private static class RecordingPublicationMediaRepository extends PublicationMediaRepository {

        private final Set<StagingMediaKey> retained;
        private final List<List<StagingMediaKey>> batches = new ArrayList<>();

        private RecordingPublicationMediaRepository(Set<StagingMediaKey> retained) {
            this.retained = new HashSet<>(retained);
        }

        @Override
        public Set<StagingMediaKey> findUploadPending(List<StagingMediaKey> keys) {
            batches.add(List.copyOf(keys));
            Set<StagingMediaKey> result = new HashSet<>(keys);
            result.retainAll(retained);
            return Set.copyOf(result);
        }

    }

}
