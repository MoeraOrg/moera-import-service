package org.moera.importer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moera.importer.admin.AdministrationService;
import org.moera.importer.application.ChannelProcessor;
import org.moera.importer.application.PublicationBuilder;
import org.moera.importer.application.PublicationMediaProcessor;
import org.moera.importer.application.PublicationPayload;
import org.moera.importer.application.PublicationProcessor;
import org.moera.importer.application.SourceEventProcessor;
import org.moera.importer.config.CreateRateProperties;
import org.moera.importer.model.AccessState;
import org.moera.importer.model.BodyFormat;
import org.moera.importer.model.Destination;
import org.moera.importer.model.Media;
import org.moera.importer.model.SourceType;
import org.moera.importer.model.SubscriptionState;
import org.moera.importer.moera.CreatedPosting;
import org.moera.importer.moera.DestinationCapabilities;
import org.moera.importer.moera.MediaUpload;
import org.moera.importer.moera.MoeraService;
import org.moera.importer.moera.UploadedMedia;
import org.moera.importer.persistence.ChannelRepository;
import org.moera.importer.persistence.DestinationCreateSlot;
import org.moera.importer.persistence.DestinationRepository;
import org.moera.importer.persistence.JooqMappings;
import org.moera.importer.persistence.PostgresIntegrationTest;
import org.moera.importer.persistence.PublicationMediaClaim;
import org.moera.importer.persistence.PublicationMediaRepository;
import org.moera.importer.persistence.PublicationRepository;
import org.moera.importer.source.AccessResult;
import org.moera.importer.source.ChannelResolution;
import org.moera.importer.source.ChannelSnapshot;
import org.moera.importer.source.GroupingRelation;
import org.moera.importer.source.InitialImportMode;
import org.moera.importer.source.MediaSnapshot;
import org.moera.importer.source.PollResult;
import org.moera.importer.source.SourceAdapter;
import org.moera.importer.source.SourceEvent;
import org.moera.importer.source.SourceEventSink;
import org.moera.importer.source.SourceLinkPreview;
import org.moera.importer.source.SourceMedia;
import org.moera.importer.source.SourceMessage;
import org.moera.importer.source.SubscriptionResult;
import org.moera.lib.node.types.SourceFormat;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.moera.importer.admin.AdministrationModels.ChannelCreate;
import static org.moera.importer.persistence.jooq.tables.Channels.CHANNELS;
import static org.moera.importer.persistence.jooq.tables.Destinations.DESTINATIONS;
import static org.moera.importer.persistence.jooq.tables.Media.MEDIA;
import static org.moera.importer.persistence.jooq.tables.Messages.MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Mirrors.MIRRORS;
import static org.moera.importer.persistence.jooq.tables.PublicationMedia.PUBLICATION_MEDIA;
import static org.moera.importer.persistence.jooq.tables.PublicationMessages.PUBLICATION_MESSAGES;
import static org.moera.importer.persistence.jooq.tables.PublicationStories.PUBLICATION_STORIES;
import static org.moera.importer.persistence.jooq.tables.Publications.PUBLICATIONS;

@SpringBootTest(
    classes = { MoeraImportApplication.class, ImportPipelineTest.Configuration.class },
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class ImportPipelineTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final String CHANNEL_SOURCE_ID = "channel-1";
    private static final String FIRST_MESSAGE_ID = "1";
    private static final String SKIPPED_MESSAGE_ID = "2";
    private static final String SECOND_MESSAGE_ID = "3";
    private static final String SHARED_PREVIEW_URL = "https://example.com/shared";
    private static final String CHANGED_PREVIEW_URL = "https://example.com/changed";

    @Inject
    private AdministrationService administrationService;

    @Inject
    private ChannelProcessor channelProcessor;

    @Inject
    private ChannelRepository channelRepository;

    @Inject
    private DSLContext dslContext;

    @Inject
    private FakeMoeraService fakeMoeraService;

    @Inject
    private FakeSourceAdapter fakeSourceAdapter;

    @Inject
    private JooqMappings jooqMappings;

    @Inject
    private PublicationBuilder publicationBuilder;

    @Inject
    private PublicationMediaProcessor publicationMediaProcessor;

    @Inject
    private PublicationMediaRepository publicationMediaRepository;

    @Inject
    private PublicationProcessor publicationProcessor;

    @Inject
    private PublicationRepository publicationRepository;

    private final RecordingDestinationRepository recordingDestinationRepository =
        new RecordingDestinationRepository();

    @Inject
    private SourceEventProcessor sourceEventProcessor;

    @LocalServerPort
    private int serverPort;

    @Inject
    private TestClock testClock;

    @BeforeEach
    void resetState() {
        dslContext.truncate(CHANNELS).restartIdentity().cascade().execute();
        dslContext.truncate(DESTINATIONS).restartIdentity().cascade().execute();
        fakeMoeraService.reset();
        fakeSourceAdapter.reset();
        recordingDestinationRepository.reset();
        testClock.reset();
        CreateRateProperties createRateProperties = new CreateRateProperties();
        createRateProperties.setCount(10);
        createRateProperties.setPeriod(Duration.ofMinutes(1));
        ReflectionTestUtils.setField(
            publicationProcessor, "createRateProperties", createRateProperties
        );
        ReflectionTestUtils.setField(
            publicationProcessor, "destinationRepository", recordingDestinationRepository
        );
    }

    @Test
    void importsAndSynchronizesCompletePublicationLifecycle() {
        PipelineIds ids = insertPipeline();
        assertThat(fakeSourceAdapter.initialImportMode).isEqualTo(InitialImportMode.RECENT);
        SourceMessage first = firstMessage(false, SHARED_PREVIEW_URL, hash(1));
        SourceMessage second = secondMessage();
        fakeSourceAdapter.pollResult = new PollResult(
            List.of(first, second), objectNode().put("messageId", 3)
        );
        assertThat(fakeSourceAdapter.pollResult.messages()).hasSizeLessThanOrEqualTo(20);

        channelProcessor.process(channelRepository.claimPolls(1, LEASE).getFirst());

        assertThat(dslContext.select(MESSAGES.SOURCE_ID).from(MESSAGES).orderBy(MESSAGES.SOURCE_ID)
            .fetch(MESSAGES.SOURCE_ID)).containsExactly(FIRST_MESSAGE_ID, SECOND_MESSAGE_ID);
        assertThat(dslContext.fetchExists(MESSAGES, MESSAGES.SOURCE_ID.eq(SKIPPED_MESSAGE_ID))).isFalse();
        assertThat(jooqMappings.jsonNode(dslContext.select(CHANNELS.WATERLINE).from(CHANNELS)
            .fetchSingle(CHANNELS.WATERLINE)).path("messageId").asLong()).isEqualTo(3);
        assertThat(messageRevision(FIRST_MESSAGE_ID)).isZero();
        assertThat(messageRevision(SECOND_MESSAGE_ID)).isZero();

        publicationBuilder.build(ids.mirrorId());

        long publicationId = dslContext.select(PUBLICATIONS.ID).from(PUBLICATIONS)
            .fetchSingle(PUBLICATIONS.ID);
        List<Long> originalMembership = publicationMembership(publicationId);
        assertThat(originalMembership).hasSize(2);
        assertThat(effectiveMediaSourceIds(publicationId)).containsExactly("preview-one", "video");

        assertThat(processMediaBatch()).hasSize(2);
        assertThat(publicationRepository.claimReady(1, LEASE)).isEmpty();
        testClock.advance(Duration.ofSeconds(10));
        assertThat(processMediaBatch()).hasSize(1);
        assertThat(publicationRepository.claimReady(1, LEASE)).isEmpty();
        testClock.advance(Duration.ofSeconds(10));
        assertThat(processMediaBatch()).hasSize(1);
        assertThat(publicationMediaRepository.claimPending(1, LEASE)).isEmpty();

        processPublication();

        assertThat(publicationRevision()).containsExactly(0, 0);
        assertThat(recordingDestinationRepository.acquisitions).isEqualTo(1);
        assertThat(recordingDestinationRepository.interval).isEqualTo(Duration.ofSeconds(6));
        assertThat(fakeMoeraService.createdPayloads).singleElement().satisfies(payload -> {
            assertThat(payload.bodySrc().getLinkPreviews()).singleElement().satisfies(preview -> {
                assertThat(preview.getUrl()).isEqualTo(SHARED_PREVIEW_URL);
                assertThat(preview.getTitle()).isEqualTo("First preview");
                assertThat(preview.getImageHash()).isEqualTo("preview-one-hash");
            });
            assertThat(payload.media()).extracting(media -> media.getLocalMediaId())
                .containsExactly("preview-one-id", "video-compressed-id");
            assertThat(payload.publications()).singleElement().satisfies(story -> {
                assertThat(story.getFeedName()).isEqualTo("timeline");
                assertThat(story.getPinned()).isFalse();
            });
        });
        assertThat(dslContext.select(PUBLICATION_STORIES.STORY_ID).from(PUBLICATION_STORIES)
            .where(PUBLICATION_STORIES.FEED_NAME.eq("timeline"))
            .fetchSingle(PUBLICATION_STORIES.STORY_ID)).isEqualTo("story-1");

        sourceEventProcessor.accept(new SourceEvent.MessageChanged(
            SourceType.TELEGRAM,
            CHANNEL_SOURCE_ID,
            firstMessage(true, CHANGED_PREVIEW_URL, hash(1))
        ));

        assertThat(messageRevision(FIRST_MESSAGE_ID)).isOne();
        assertThat(publicationRevision()).containsExactly(1, 0);
        assertThat(publicationMembership(publicationId)).isEqualTo(originalMembership);
        assertThat(effectiveMediaSourceIds(publicationId))
            .containsExactly("preview-one", "video", "preview-two");
        assertThat(publicationRepository.claimReady(1, LEASE)).isEmpty();
        assertThat(processMediaBatch()).hasSize(1);

        processPublication();

        assertThat(publicationRevision()).containsExactly(1, 1);
        assertThat(recordingDestinationRepository.acquisitions).isEqualTo(1);
        assertThat(fakeMoeraService.updatedPayloads.getFirst().bodySrc().getLinkPreviews())
            .extracting(preview -> preview.getUrl())
            .containsExactly(CHANGED_PREVIEW_URL, SHARED_PREVIEW_URL);
        assertThat(fakeMoeraService.updatedPayloads.getFirst().bodySrc().getLinkPreviews())
            .extracting(preview -> preview.getImageHash())
            .containsExactly("preview-one-hash", "preview-two-hash");
        assertThat(fakeMoeraService.updatedPayloads.getFirst().pinned()).isTrue();

        sourceEventProcessor.accept(new SourceEvent.MessageDeleted(
            SourceType.TELEGRAM, CHANNEL_SOURCE_ID, FIRST_MESSAGE_ID
        ));

        assertThat(messageRevision(FIRST_MESSAGE_ID)).isEqualTo(2);
        assertThat(publicationRevision()).containsExactly(2, 1);
        assertThat(publicationMembership(publicationId)).isEqualTo(originalMembership);
        processPublication();

        assertThat(publicationRevision()).containsExactly(2, 2);
        assertThat(fakeMoeraService.updatedPayloads.get(1).bodySrc().getText()).isEqualTo("<p>Second</p>");
        assertThat(fakeMoeraService.updatedPayloads.get(1).pinned()).isFalse();

        sourceEventProcessor.accept(new SourceEvent.MessageDeleted(
            SourceType.TELEGRAM, CHANNEL_SOURCE_ID, SECOND_MESSAGE_ID
        ));

        assertThat(messageRevision(SECOND_MESSAGE_ID)).isOne();
        assertThat(publicationRevision()).containsExactly(3, 2);
        assertThat(publicationMembership(publicationId)).isEqualTo(originalMembership);
        processPublication();

        assertThat(publicationRevision()).containsExactly(3, 3);
        assertThat(dslContext.select(PUBLICATIONS.DESTINATION_POST_ID).from(PUBLICATIONS)
            .fetchSingle(PUBLICATIONS.DESTINATION_POST_ID)).isNull();
        assertThat(dslContext.select(PUBLICATIONS.DESTINATION_PINNED).from(PUBLICATIONS)
            .fetchSingle(PUBLICATIONS.DESTINATION_PINNED)).isNull();
        assertThat(dslContext.fetchCount(PUBLICATION_STORIES)).isZero();
        assertThat(fakeSourceAdapter.downloadedMedia)
            .containsExactly("preview-one", "video", "preview-two");
        assertThat(fakeMoeraService.uploadedMedia)
            .containsExactly("preview-one", "video", "preview-two");
        assertThat(fakeMoeraService.operations).containsExactly(
            "upload:preview-one",
            "upload:video",
            "compression:video-original-id",
            "compression:video-original-id",
            "create",
            "upload:preview-two",
            "update",
            "story:story-1:true",
            "update",
            "story:story-1:false",
            "delete"
        );
        assertThat(recordingDestinationRepository.acquisitions).isEqualTo(1);
    }

    @Test
    void exposesOperationalEndpoints() throws IOException, InterruptedException {
        assertEndpoint("/actuator/health/liveness", "\"status\":\"UP\"");
        assertEndpoint("/actuator/health/readiness", "\"status\":\"UP\"");
        assertEndpoint("/actuator/prometheus", "# HELP");
    }

    private PipelineIds insertPipeline() {
        long channelId = administrationService.createChannel(new ChannelCreate(
            SourceType.TELEGRAM, "https://t.me/channel-1", false
        )).id();
        long destinationId = dslContext.insertInto(DESTINATIONS)
            .set(DESTINATIONS.NODE_NAME, "destination.example")
            .returningResult(DESTINATIONS.ID)
            .fetchSingle(DESTINATIONS.ID);
        long mirrorId = dslContext.insertInto(MIRRORS)
            .set(MIRRORS.CHANNEL_ID, channelId)
            .set(MIRRORS.DESTINATION_ID, destinationId)
            .set(MIRRORS.FEEDS, new String[] { "timeline" })
            .returningResult(MIRRORS.ID)
            .fetchSingle(MIRRORS.ID);
        return new PipelineIds(mirrorId);
    }

    private SourceMessage firstMessage(boolean pinned, String previewUrl, byte[] bodyHash) {
        return message(
            FIRST_MESSAGE_ID,
            pinned,
            NOW.minusSeconds(120),
            "<p>First</p>",
            bodyHash,
            List.of(
                media("preview-one", 0, "image/jpeg", "preview-one.jpg", hash(11)),
                media("video", 1, "video/mp4", "video.mp4", hash(12))
            ),
            List.of(preview(previewUrl, "First preview", "preview-one"))
        );
    }

    private SourceMessage secondMessage() {
        return message(
            SECOND_MESSAGE_ID,
            false,
            NOW.minusSeconds(90),
            "<p>Second</p>",
            hash(3),
            List.of(media("preview-two", 0, "image/jpeg", "preview-two.jpg", hash(13))),
            List.of(preview(SHARED_PREVIEW_URL, "Duplicate preview", "preview-two"))
        );
    }

    private SourceMessage message(
        String sourceId,
        boolean pinned,
        Instant publishedAt,
        String body,
        byte[] bodyHash,
        List<SourceMedia> media,
        List<SourceLinkPreview> previews
    ) {
        return new SourceMessage(
            sourceId,
            false,
            pinned,
            publishedAt,
            body,
            BodyFormat.HTML,
            bodyHash,
            objectNode(),
            media,
            previews
        );
    }

    private SourceMedia media(
        String sourceId,
        int position,
        String mimeType,
        String fileName,
        byte[] sourceHash
    ) {
        return new SourceMedia(
            sourceId, position, mimeType, fileName, 10L, sourceHash, objectNode()
        );
    }

    private SourceLinkPreview preview(String url, String title, String imageSourceMediaId) {
        return new SourceLinkPreview(
            "Example", url, title, "Description", NOW.minusSeconds(3600), imageSourceMediaId
        );
    }

    private List<PublicationMediaClaim> processMediaBatch() {
        List<PublicationMediaClaim> claims = publicationMediaRepository.claimPending(10, LEASE);
        claims.forEach(publicationMediaProcessor::process);
        return claims;
    }

    private void processPublication() {
        publicationProcessor.process(publicationRepository.claimReady(1, LEASE).getFirst());
    }

    private int messageRevision(String sourceId) {
        return dslContext.select(MESSAGES.REVISION)
            .from(MESSAGES)
            .where(MESSAGES.SOURCE_ID.eq(sourceId))
            .fetchSingle(MESSAGES.REVISION);
    }

    private List<Integer> publicationRevision() {
        var record = dslContext.select(PUBLICATIONS.REVISION, PUBLICATIONS.PUBLISHED_REVISION)
            .from(PUBLICATIONS)
            .fetchSingle();
        return List.of(record.value1(), record.value2());
    }

    private List<Long> publicationMembership(long publicationId) {
        return dslContext.select(PUBLICATION_MESSAGES.MESSAGE_ID)
            .from(PUBLICATION_MESSAGES)
            .where(PUBLICATION_MESSAGES.PUBLICATION_ID.eq(publicationId))
            .orderBy(PUBLICATION_MESSAGES.POSITION)
            .fetch(PUBLICATION_MESSAGES.MESSAGE_ID);
    }

    private List<String> effectiveMediaSourceIds(long publicationId) {
        return dslContext.select(MEDIA.SOURCE_ID)
            .from(PUBLICATION_MEDIA)
            .join(MEDIA).on(MEDIA.ID.eq(PUBLICATION_MEDIA.MEDIA_ID))
            .where(PUBLICATION_MEDIA.PUBLICATION_ID.eq(publicationId))
            .orderBy(MEDIA.ID)
            .fetch(MEDIA.SOURCE_ID);
    }

    private void assertEndpoint(String path, String expectedBody) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + path)).build();
        HttpResponse<String> response = HttpClient.newHttpClient()
            .send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(expectedBody);
    }

    private static ObjectNode objectNode() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static byte[] hash(int firstByte) {
        byte[] hash = new byte[32];
        hash[0] = (byte) firstByte;
        return hash;
    }

    private record PipelineIds(long mirrorId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {

        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }

        @Bean
        FakeSourceAdapter fakeSourceAdapter() {
            return new FakeSourceAdapter();
        }

        @Bean
        @Primary
        FakeMoeraService fakeMoeraService() {
            return new FakeMoeraService();
        }

    }

    static class TestClock extends Clock {

        private Instant current = NOW;

        void reset() {
            current = NOW;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

    }

    static class FakeSourceAdapter implements SourceAdapter {

        private PollResult pollResult;
        private final List<String> downloadedMedia = new ArrayList<>();
        private InitialImportMode initialImportMode;

        void reset() {
            pollResult = null;
            downloadedMedia.clear();
            initialImportMode = null;
        }

        @Override
        public SourceType type() {
            return SourceType.TELEGRAM;
        }

        @Override
        public ChannelResolution resolveChannel(URI sourceUri, InitialImportMode initialImportMode) {
            this.initialImportMode = initialImportMode;
            return new ChannelResolution(
                CHANNEL_SOURCE_ID,
                sourceUri,
                objectNode(),
                AccessState.GRANTED,
                objectNode(),
                SubscriptionState.ACTIVE,
                objectNode()
            );
        }

        @Override
        public Duration groupingThreshold() {
            return Duration.ofMinutes(5);
        }

        @Override
        public Duration settlingDelay() {
            return Duration.ZERO;
        }

        @Override
        public Duration pollingInterval(ChannelSnapshot channel) {
            return Duration.ofHours(1);
        }

        @Override
        public GroupingRelation groupingRelation(SourceMessage previous, SourceMessage next) {
            return GroupingRelation.UNKNOWN;
        }

        @Override
        public AccessResult refreshAccess(ChannelSnapshot channel) {
            return new AccessResult(AccessState.GRANTED, channel.sourceId(), objectNode());
        }

        @Override
        public SubscriptionResult refreshSubscription(ChannelSnapshot channel) {
            return new SubscriptionResult(SubscriptionState.ACTIVE, objectNode());
        }

        @Override
        public PollResult poll(ChannelSnapshot channel) {
            return pollResult;
        }

        @Override
        public void downloadMedia(ChannelSnapshot channel, MediaSnapshot media, Path destination) {
            downloadedMedia.add(media.sourceId());
            try {
                Files.write(destination, media.sourceId().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void start(SourceEventSink sink) {
        }

        @Override
        public void stop() {
        }

    }

    static class RecordingDestinationRepository extends DestinationRepository {

        private int acquisitions;
        private Duration interval;

        void reset() {
            acquisitions = 0;
            interval = null;
        }

        @Override
        public DestinationCreateSlot acquireCreateSlot(long destinationId, Duration interval) {
            acquisitions++;
            this.interval = interval;
            return new DestinationCreateSlot.Acquired();
        }

    }

    static class FakeMoeraService extends MoeraService {

        private final List<String> operations = new ArrayList<>();
        private final List<String> uploadedMedia = new ArrayList<>();
        private final List<PublicationPayload> createdPayloads = new ArrayList<>();
        private final List<PublicationPayload> updatedPayloads = new ArrayList<>();
        private int compressionChecks;

        void reset() {
            operations.clear();
            uploadedMedia.clear();
            createdPayloads.clear();
            updatedPayloads.clear();
            compressionChecks = 0;
        }

        @Override
        public DestinationCapabilities capabilities(Destination destination) {
            return new DestinationCapabilities(10_000, List.of(SourceFormat.HTML));
        }

        @Override
        public MediaUpload uploadMedia(Destination destination, Path file, Media media) {
            uploadedMedia.add(media.sourceId());
            operations.add("upload:" + media.sourceId());
            return switch (media.sourceId()) {
                case "preview-one" -> ready("preview-one-id", "preview-one-hash");
                case "preview-two" -> ready("preview-two-id", "preview-two-hash");
                case "video" -> new MediaUpload.AwaitingCompression(
                    new UploadedMedia("video-original-id", "video-original-hash")
                );
                default -> throw new IllegalArgumentException("Unexpected media " + media.sourceId());
            };
        }

        @Override
        public MediaUpload checkMediaCompression(Destination destination, String originalMediaId) {
            operations.add("compression:" + originalMediaId);
            compressionChecks++;
            if (compressionChecks == 1) {
                return new MediaUpload.AwaitingCompression(
                    new UploadedMedia("video-original-id", "video-original-hash")
                );
            }
            return ready("video-compressed-id", "video-compressed-hash");
        }

        @Override
        public CreatedPosting createPost(Destination destination, PublicationPayload payload) {
            requireCompressionFinished();
            operations.add("create");
            createdPayloads.add(payload);
            Map<String, String> stories = new LinkedHashMap<>();
            stories.put("timeline", "story-1");
            return new CreatedPosting("posting-1", stories);
        }

        @Override
        public void updatePost(Destination destination, String postingId, PublicationPayload payload) {
            requireCompressionFinished();
            operations.add("update");
            updatedPayloads.add(payload);
        }

        @Override
        public void updateStory(Destination destination, String storyId, boolean pinned) {
            operations.add("story:" + storyId + ":" + pinned);
        }

        @Override
        public void deletePost(Destination destination, String postingId) {
            operations.add("delete");
        }

        private MediaUpload ready(String id, String hash) {
            return new MediaUpload.Ready(new UploadedMedia(id, hash));
        }

        private void requireCompressionFinished() {
            if (compressionChecks < 2) {
                throw new AssertionError("Posting mutation started before media compression completed");
            }
        }

    }

}
