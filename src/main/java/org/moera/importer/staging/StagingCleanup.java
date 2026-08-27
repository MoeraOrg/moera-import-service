package org.moera.importer.staging;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jakarta.inject.Inject;

import org.moera.importer.config.StorageProperties;
import org.moera.importer.persistence.PublicationMediaRepository;
import org.moera.importer.persistence.StagingMediaKey;
import org.springframework.stereotype.Component;

@Component
public class StagingCleanup {

    private static final Pattern FILE_NAME = Pattern.compile("([0-9]+)-([0-9a-fA-F]+)");

    @Inject
    private Clock clock;

    @Inject
    private PublicationMediaRepository publicationMediaRepository;

    @Inject
    private StorageProperties storageProperties;

    public void clean() {
        Path root = storageProperties.getStagingDirectory().toAbsolutePath().normalize();
        if (Files.notExists(root)) {
            return;
        }
        Instant cutoff = clock.instant().minus(storageProperties.getStagingGracePeriod());
        List<StagingFile> candidates = findCandidates(root, cutoff);
        deleteOrphans(candidates);
        deleteEmptyDirectories(root);
    }

    private List<StagingFile> findCandidates(Path root, Instant cutoff) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> lastModified(path).compareTo(cutoff) <= 0)
                .map(path -> stagingFile(root, path))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot scan staging directory", e);
        }
    }

    private Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read staging file timestamp", e);
        }
    }

    private StagingFile stagingFile(Path root, Path path) {
        Path relative = root.relativize(path);
        if (relative.getNameCount() != 2) {
            return new StagingFile(path, null);
        }
        try {
            long publicationId = Long.parseLong(relative.getName(0).toString());
            Matcher matcher = FILE_NAME.matcher(relative.getFileName().toString());
            if (!matcher.matches() || matcher.group(2).length() % 2 != 0) {
                return new StagingFile(path, null);
            }
            long mediaId = Long.parseLong(matcher.group(1));
            String sourceHash = matcher.group(2).toLowerCase(Locale.ROOT);
            return new StagingFile(path, new StagingMediaKey(publicationId, mediaId, sourceHash));
        } catch (NumberFormatException e) {
            return new StagingFile(path, null);
        }
    }

    private void deleteOrphans(List<StagingFile> candidates) {
        int batchSize = storageProperties.getCleanupBatchSize();
        if (batchSize <= 0) {
            throw new IllegalArgumentException("cleanupBatchSize must be positive");
        }
        for (int start = 0; start < candidates.size(); start += batchSize) {
            List<StagingFile> batch = candidates.subList(start, Math.min(start + batchSize, candidates.size()));
            List<StagingMediaKey> keys = batch.stream()
                .map(StagingFile::key)
                .filter(java.util.Objects::nonNull)
                .toList();
            Set<StagingMediaKey> retained = keys.isEmpty()
                ? Set.of() : publicationMediaRepository.findUploadPending(keys);
            batch.stream()
                .filter(file -> file.key() == null || !retained.contains(file.key()))
                .map(StagingFile::path)
                .forEach(this::delete);
        }
    }

    private void deleteEmptyDirectories(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> directories = paths
                .filter(Files::isDirectory)
                .filter(path -> !path.equals(root))
                .sorted(Comparator.reverseOrder())
                .toList();
            directories.forEach(this::deleteIfEmpty);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot scan staging directories", e);
        }
    }

    private void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete stale staging file", e);
        }
    }

    private void deleteIfEmpty(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            if (entries.findAny().isEmpty()) {
                Files.deleteIfExists(directory);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete empty staging directory", e);
        }
    }

    private record StagingFile(Path path, StagingMediaKey key) {
    }

}
