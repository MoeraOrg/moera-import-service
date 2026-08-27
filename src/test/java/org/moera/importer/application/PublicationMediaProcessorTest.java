package org.moera.importer.application;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.moera.importer.config.WorkerProperties;
import org.moera.importer.model.AccessState;
import org.moera.importer.model.Destination;
import org.moera.importer.model.Media;
import org.moera.importer.model.MediaSkipReason;
import org.moera.importer.model.SourceType;
import org.moera.importer.model.SubscriptionState;
import org.moera.importer.moera.DestinationCapabilities;
import org.moera.importer.moera.MediaUpload;
import org.moera.importer.moera.MoeraPermanentException;
import org.moera.importer.moera.MoeraService;
import org.moera.importer.moera.MoeraTemporaryException;
import org.moera.importer.moera.UploadedMedia;
import org.moera.importer.persistence.PublicationMediaClaim;
import org.moera.importer.persistence.PublicationMediaContext;
import org.moera.importer.persistence.PublicationMediaRepository;
import org.moera.importer.source.AccessResult;
import org.moera.importer.source.ChannelResolution;
import org.moera.importer.source.ChannelSnapshot;
import org.moera.importer.source.GroupingRelation;
import org.moera.importer.source.MediaSnapshot;
import org.moera.importer.source.PollResult;
import org.moera.importer.source.SourceAdapter;
import org.moera.importer.source.SourceAdapterRegistry;
import org.moera.importer.source.SourceEventSink;
import org.moera.importer.source.SourceMessage;
import org.moera.importer.source.SubscriptionResult;
import org.moera.importer.staging.StagingFileManager;
import org.moera.lib.node.types.SourceFormat;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class PublicationMediaProcessorTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    @TempDir
    private Path tempDirectory;

    private PublicationMediaProcessor publicationMediaProcessor;
    private RecordingPublicationMediaRepository publicationMediaRepository;
    private FakeMoeraService moeraService;
    private FakeSourceAdapter sourceAdapter;
    private StagingFileManager stagingFileManager;
    private WorkerProperties workerProperties;

    @BeforeEach
    void configureProcessor() {
        publicationMediaProcessor = new PublicationMediaProcessor();
        publicationMediaRepository = new RecordingPublicationMediaRepository();
        moeraService = new FakeMoeraService();
        sourceAdapter = new FakeSourceAdapter();
        workerProperties = new WorkerProperties();
        workerProperties.setMediaCompressionPollInterval(Duration.ofSeconds(10));
        workerProperties.setStagingDirectory(tempDirectory);
        stagingFileManager = new StagingFileManager();
        ReflectionTestUtils.setField(stagingFileManager, "workerProperties", workerProperties);
        ReflectionTestUtils.setField(publicationMediaProcessor, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(publicationMediaProcessor, "moeraService", moeraService);
        ReflectionTestUtils.setField(
            publicationMediaProcessor,
            "publicationMediaRepository",
            publicationMediaRepository
        );
        ReflectionTestUtils.setField(
            publicationMediaProcessor,
            "retryPolicy",
            new RetryPolicy(Duration.ofMinutes(1), Duration.ofHours(1), 0, () -> 0.5)
        );
        ReflectionTestUtils.setField(
            publicationMediaProcessor,
            "sourceAdapterRegistry",
            new FakeSourceAdapterRegistry(sourceAdapter)
        );
        ReflectionTestUtils.setField(publicationMediaProcessor, "stagingFileManager", stagingFileManager);
        ReflectionTestUtils.setField(publicationMediaProcessor, "workerProperties", workerProperties);
    }

    @Test
    void skipsKnownOversizedMediaWithoutDownloading() {
        publicationMediaRepository.context = context(4L, JsonNodeFactory.instance.objectNode(), null);
        moeraService.capabilities = capabilities(3);

        publicationMediaProcessor.process(claim());

        assertThat(publicationMediaRepository.skippedReason).isEqualTo(MediaSkipReason.TOO_LARGE);
        assertThat(sourceAdapter.downloads).isZero();
        assertThat(moeraService.uploads).isZero();
    }

    @Test
    void skipsOversizedMediaAfterDownloadingAndDeletesStaging() {
        publicationMediaRepository.context = context(null, JsonNodeFactory.instance.objectNode(), null);
        moeraService.capabilities = capabilities(3);
        sourceAdapter.content = new byte[] {1, 2, 3, 4};
        Path path = stagingPath();

        publicationMediaProcessor.process(claim());

        assertThat(publicationMediaRepository.skippedReason).isEqualTo(MediaSkipReason.TOO_LARGE);
        assertThat(sourceAdapter.downloads).isOne();
        assertThat(path).doesNotExist();
    }

    @Test
    void reusesExistingStagingAndStoresSeparateUploadIdAndHash() throws IOException {
        publicationMediaRepository.context = context(null, JsonNodeFactory.instance.objectNode(), null);
        moeraService.capabilities = capabilities(10);
        moeraService.uploadResult = new MediaUpload.Ready(new UploadedMedia("media-id", "media-hash"));
        Path path = stagingPath();
        Files.write(path, new byte[] {1, 2});

        publicationMediaProcessor.process(claim());

        assertThat(sourceAdapter.downloads).isZero();
        assertThat(publicationMediaRepository.uploadedId).isEqualTo("media-id");
        assertThat(publicationMediaRepository.destinationData.path("hash").asText()).isEqualTo("media-hash");
        assertThat(path).doesNotExist();
    }

    @Test
    void persistsCompressionWaitAndDeletesStaging() {
        publicationMediaRepository.context = context(2L, JsonNodeFactory.instance.objectNode(), null);
        moeraService.capabilities = capabilities(10);
        moeraService.uploadResult = new MediaUpload.AwaitingCompression(
            new UploadedMedia("original-id", "original-hash")
        );
        Path path = stagingPath();

        publicationMediaProcessor.process(claim());

        assertThat(publicationMediaRepository.awaitingId).isEqualTo("original-id");
        assertThat(publicationMediaRepository.destinationData.path("hash").asText()).isEqualTo("original-hash");
        assertThat(publicationMediaRepository.nextAttemptAt).isEqualTo(NOW.plusSeconds(10));
        assertThat(path).doesNotExist();
    }

    @Test
    void compressionNotReadyIsRescheduledWithoutSourceOrUpload() {
        JsonNode data = JsonNodeFactory.instance.objectNode()
            .put("hash", "original-hash")
            .put("compressionPending", true);
        publicationMediaRepository.context = context(2L, data, "original-id");
        moeraService.compressionResult = new MediaUpload.AwaitingCompression(
            new UploadedMedia("original-id", "original-hash")
        );

        publicationMediaProcessor.process(claim());

        assertThat(moeraService.compressionChecks).isOne();
        assertThat(moeraService.capabilityRequests).isZero();
        assertThat(moeraService.uploads).isZero();
        assertThat(sourceAdapter.downloads).isZero();
        assertThat(publicationMediaRepository.awaitingId).isEqualTo("original-id");
        assertThat(publicationMediaRepository.nextAttemptAt).isEqualTo(NOW.plusSeconds(10));
        assertThat(publicationMediaRepository.error).isNull();
    }

    @Test
    void compressionReadyReplacesOriginalIdAndHash() {
        JsonNode data = JsonNodeFactory.instance.objectNode()
            .put("hash", "original-hash")
            .put("compressionPending", true);
        publicationMediaRepository.context = context(2L, data, "original-id");
        moeraService.compressionResult = new MediaUpload.Ready(
            new UploadedMedia("compressed-id", "compressed-hash")
        );

        publicationMediaProcessor.process(claim());

        assertThat(publicationMediaRepository.uploadedId).isEqualTo("compressed-id");
        assertThat(publicationMediaRepository.destinationData.path("hash").asText()).isEqualTo("compressed-hash");
        assertThat(publicationMediaRepository.destinationData.has("compressionPending")).isFalse();
        assertThat(sourceAdapter.downloads).isZero();
        assertThat(moeraService.uploads).isZero();
    }

    @Test
    void temporaryUploadFailureSchedulesRetryAndRetainsStaging() {
        publicationMediaRepository.context = context(2L, JsonNodeFactory.instance.objectNode(), null);
        moeraService.capabilities = capabilities(10);
        moeraService.failure = new MoeraTemporaryException("temporary", null);
        Path path = stagingPath();

        publicationMediaProcessor.process(claim());

        assertThat(publicationMediaRepository.nextAttemptAt).isEqualTo(NOW.plus(Duration.ofMinutes(4)));
        assertThat(publicationMediaRepository.error).isEqualTo("temporary");
        assertThat(path).exists();
    }

    @Test
    void permanentUploadFailureStopsSchedulingAndDeletesStaging() {
        publicationMediaRepository.context = context(2L, JsonNodeFactory.instance.objectNode(), null);
        moeraService.capabilities = capabilities(10);
        moeraService.failure = new MoeraPermanentException("permanent", null);
        Path path = stagingPath();

        publicationMediaProcessor.process(claim());

        assertThat(publicationMediaRepository.failed).isTrue();
        assertThat(publicationMediaRepository.error).isEqualTo("permanent");
        assertThat(path).doesNotExist();
    }

    @Test
    void staleFinalCompletionUsesExpectedSourceHashAndDeletesOldStaging() {
        publicationMediaRepository.context = context(2L, JsonNodeFactory.instance.objectNode(), null);
        publicationMediaRepository.completionAccepted = false;
        moeraService.capabilities = capabilities(10);
        moeraService.uploadResult = new MediaUpload.Ready(new UploadedMedia("media-id", "media-hash"));
        Path path = stagingPath();

        publicationMediaProcessor.process(claim());

        assertThat(publicationMediaRepository.expectedSourceHash).containsExactly(hash(1));
        assertThat(publicationMediaRepository.uploadedId).isEqualTo("media-id");
        assertThat(path).doesNotExist();
        assertThat(publicationMediaRepository.error).isNull();
    }

    private PublicationMediaClaim claim() {
        return new PublicationMediaClaim(17, 23, UUID.randomUUID(), 3);
    }

    private PublicationMediaContext context(Long size, JsonNode destinationData, String destinationMediaId) {
        JsonNode empty = JsonNodeFactory.instance.objectNode();
        ChannelSnapshot channel = new ChannelSnapshot(
            5,
            SourceType.TELEGRAM,
            "channel-1",
            URI.create("https://t.me/channel-1"),
            empty,
            AccessState.GRANTED,
            empty,
            SubscriptionState.ACTIVE,
            empty,
            empty
        );
        Destination destination = new Destination(7, "destination.example", empty, empty, NOW, NOW);
        Media media = new Media(
            23,
            11,
            "media-1",
            0,
            "image/jpeg",
            "media.jpg",
            size,
            hash(1),
            empty,
            NOW,
            NOW
        );
        return new PublicationMediaContext(17, destination, channel, media, destinationMediaId, destinationData);
    }

    private DestinationCapabilities capabilities(long mediaMaxSize) {
        return new DestinationCapabilities(mediaMaxSize, List.of(SourceFormat.HTML));
    }

    private Path stagingPath() {
        return stagingFileManager.path(17, 23, hash(1));
    }

    private byte[] hash(int firstByte) {
        byte[] hash = new byte[32];
        hash[0] = (byte) firstByte;
        return hash;
    }

    private static class FakeSourceAdapterRegistry extends SourceAdapterRegistry {

        private final SourceAdapter sourceAdapter;

        FakeSourceAdapterRegistry(SourceAdapter sourceAdapter) {
            this.sourceAdapter = sourceAdapter;
        }

        @Override
        public SourceAdapter get(SourceType sourceType) {
            return sourceAdapter;
        }

    }

    private static class RecordingPublicationMediaRepository extends PublicationMediaRepository {

        private PublicationMediaContext context;
        private MediaSkipReason skippedReason;
        private String awaitingId;
        private String uploadedId;
        private JsonNode destinationData;
        private byte[] expectedSourceHash;
        private Instant nextAttemptAt;
        private String error;
        private boolean failed;
        private boolean completionAccepted = true;

        @Override
        public PublicationMediaContext loadContext(long publicationId, long mediaId) {
            return context;
        }

        @Override
        public boolean awaitingCompression(
            long publicationId,
            long mediaId,
            UUID token,
            byte[] expectedSourceHash,
            String originalMediaId,
            JsonNode destinationData,
            Instant nextAttemptAt
        ) {
            this.expectedSourceHash = expectedSourceHash.clone();
            awaitingId = originalMediaId;
            this.destinationData = destinationData;
            this.nextAttemptAt = nextAttemptAt;
            return completionAccepted;
        }

        @Override
        public boolean uploaded(
            long publicationId,
            long mediaId,
            UUID token,
            byte[] expectedSourceHash,
            String destinationMediaId,
            JsonNode destinationData
        ) {
            this.expectedSourceHash = expectedSourceHash.clone();
            uploadedId = destinationMediaId;
            this.destinationData = destinationData;
            return completionAccepted;
        }

        @Override
        public boolean skipped(
            long publicationId,
            long mediaId,
            UUID token,
            byte[] expectedSourceHash,
            MediaSkipReason reason
        ) {
            this.expectedSourceHash = expectedSourceHash.clone();
            skippedReason = reason;
            return completionAccepted;
        }

        @Override
        public boolean retry(
            long publicationId,
            long mediaId,
            UUID token,
            Instant nextAttemptAt,
            String error
        ) {
            this.nextAttemptAt = nextAttemptAt;
            this.error = error;
            return completionAccepted;
        }

        @Override
        public boolean fail(long publicationId, long mediaId, UUID token, String error) {
            failed = true;
            this.error = error;
            return completionAccepted;
        }

    }

    private static class FakeMoeraService extends MoeraService {

        private DestinationCapabilities capabilities;
        private MediaUpload uploadResult;
        private MediaUpload compressionResult;
        private RuntimeException failure;
        private int capabilityRequests;
        private int uploads;
        private int compressionChecks;

        @Override
        public DestinationCapabilities capabilities(Destination destination) {
            capabilityRequests++;
            return capabilities;
        }

        @Override
        public MediaUpload uploadMedia(Destination destination, Path file, Media media) {
            uploads++;
            throwIfFailed();
            return uploadResult;
        }

        @Override
        public MediaUpload checkMediaCompression(Destination destination, String originalMediaId) {
            compressionChecks++;
            throwIfFailed();
            return compressionResult;
        }

        private void throwIfFailed() {
            if (failure != null) {
                throw failure;
            }
        }

    }

    private static class FakeSourceAdapter implements SourceAdapter {

        private byte[] content = {1, 2};
        private int downloads;

        @Override
        public SourceType type() {
            return SourceType.TELEGRAM;
        }

        @Override
        public void downloadMedia(ChannelSnapshot channel, MediaSnapshot media, Path destination) {
            downloads++;
            try {
                Files.write(destination, content);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public ChannelResolution resolveChannel(URI sourceUri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Duration groupingThreshold() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Duration settlingDelay() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Duration pollingInterval(ChannelSnapshot channel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GroupingRelation groupingRelation(SourceMessage previous, SourceMessage next) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccessResult refreshAccess(ChannelSnapshot channel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SubscriptionResult refreshSubscription(ChannelSnapshot channel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PollResult poll(ChannelSnapshot channel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void start(SourceEventSink sink) {
        }

        @Override
        public void stop() {
        }

    }

}
