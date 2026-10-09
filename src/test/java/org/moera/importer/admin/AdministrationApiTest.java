package org.moera.importer.admin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moera.importer.MoeraImportApplication;
import org.moera.importer.model.AccessState;
import org.moera.importer.model.SourceType;
import org.moera.importer.model.SubscriptionState;
import org.moera.importer.persistence.ChannelRepository;
import org.moera.importer.persistence.PostgresIntegrationTest;
import org.moera.importer.source.AccessResult;
import org.moera.importer.source.ChannelMetadata;
import org.moera.importer.source.ChannelResolution;
import org.moera.importer.source.ChannelSnapshot;
import org.moera.importer.source.GroupingRelation;
import org.moera.importer.source.InitialImportMode;
import org.moera.importer.source.MediaSnapshot;
import org.moera.importer.source.PollResult;
import org.moera.importer.source.SourceAdapter;
import org.moera.importer.source.SourceEventSink;
import org.moera.importer.source.SourceMessage;
import org.moera.importer.source.SubscriptionResult;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.moera.importer.persistence.jooq.tables.Channels.CHANNELS;
import static org.moera.importer.persistence.jooq.tables.Destinations.DESTINATIONS;
import static org.moera.importer.persistence.jooq.tables.Mirrors.MIRRORS;

@SpringBootTest(
    classes = { MoeraImportApplication.class, AdministrationApiTest.Configuration.class },
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class AdministrationApiTest extends PostgresIntegrationTest {

    @Inject
    private DSLContext dslContext;

    @Inject
    private ChannelRepository channelRepository;

    @Inject
    private FakeSourceAdapter fakeSourceAdapter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @LocalServerPort
    private int serverPort;

    @BeforeEach
    void resetState() {
        dslContext.truncate(CHANNELS).restartIdentity().cascade().execute();
        dslContext.truncate(DESTINATIONS).restartIdentity().cascade().execute();
        fakeSourceAdapter.reset();
    }

    @Test
    void managesChannelsDestinationsAndMirrors() throws Exception {
        HttpResponse<String> channelCreated = request("POST", "/api/channels", objectNode()
            .put("sourceType", "TELEGRAM")
            .put("sourceUri", "https://t.me/input"));

        assertThat(channelCreated.statusCode()).isEqualTo(201);
        JsonNode channel = json(channelCreated);
        assertThat(channel.path("id").asLong()).isEqualTo(1);
        assertThat(channel.path("sourceType").asText()).isEqualTo("TELEGRAM");
        assertThat(channel.path("sourceId").asText()).isEqualTo("telegram-42");
        assertThat(channel.path("sourceUri").asText()).isEqualTo("https://t.me/canonical");
        assertThat(channel.path("accessState").asText()).isEqualTo("GRANTED");
        assertThat(channel.path("subscriptionState").asText()).isEqualTo("INACTIVE");
        assertThat(fakeSourceAdapter.resolvedOutsideTransaction).isTrue();
        assertThat(fakeSourceAdapter.resolvedOnVirtualThread).isTrue();
        var channelData = dslContext.select(
            CHANNELS.SOURCE_DATA,
            CHANNELS.INITIAL_IMPORT_MODE,
            CHANNELS.TITLE,
            CHANNELS.DESCRIPTION_HTML,
            CHANNELS.PUBLIC_USERNAME
        ).from(CHANNELS).fetchSingle();
        assertThat(objectMapper.readTree(channelData.value1().data()).path("adapterState").asText())
            .isEqualTo("kept-separate");
        assertThat(objectMapper.readTree(channelData.value1().data()).has("profile")).isFalse();
        assertThat(channelData.value2().getLiteral()).isEqualTo("RECENT");
        assertThat(channelData.value3()).isEqualTo("Canonical channel");
        assertThat(channelData.value4()).isEqualTo("<p>Description</p>");
        assertThat(channelData.value5()).isEqualTo("canonical");
        assertThat(dslContext.select(CHANNELS.ACCESS_RETRY_AT, CHANNELS.SUBSCRIPTION_RETRY_AT,
                CHANNELS.NEXT_POLL_AT)
            .from(CHANNELS)
            .fetchSingle())
            .satisfies(record -> {
                assertThat(record.value1()).isNull();
                assertThat(record.value2()).isNotNull();
                assertThat(record.value3()).isNotNull();
            });

        HttpResponse<String> channels = request("GET", "/api/channels", null);
        assertThat(channels.statusCode()).isEqualTo(200);
        assertThat(json(channels)).hasSize(1);
        assertThat(json(channels).path(0).path("sourceId").asText()).isEqualTo("telegram-42");

        HttpResponse<String> destinationCreated = request("POST", "/api/destinations", objectNode()
            .put("nodeName", "destination-node"));
        assertThat(destinationCreated.statusCode()).isEqualTo(201);
        JsonNode destination = json(destinationCreated);
        assertThat(destination.path("id").asLong()).isEqualTo(1);
        assertThat(destination.path("nodeName").asText()).isEqualTo("destination-node_0");
        assertThat(destination.has("credentialRef")).isFalse();
        assertThat(destination.path("profileSync").asBoolean()).isTrue();

        HttpResponse<String> destinations = request("GET", "/api/destinations", null);
        assertThat(destinations.statusCode()).isEqualTo(200);
        assertThat(json(destinations)).hasSize(1);

        ObjectNode mirrorRequest = objectNode()
            .put("channelId", 1)
            .put("destinationId", 1);
        mirrorRequest.putArray("feeds").add("timeline").add("news");
        HttpResponse<String> mirrorCreated = request("POST", "/api/mirrors", mirrorRequest);
        assertThat(mirrorCreated.statusCode()).isEqualTo(201);
        JsonNode mirror = json(mirrorCreated);
        assertThat(mirror.path("id").asLong()).isEqualTo(1);
        assertThat(mirror.path("channelId").asLong()).isEqualTo(1);
        assertThat(mirror.path("destinationId").asLong()).isEqualTo(1);
        assertThat(mirror.path("feeds")).containsExactly(
            JsonNodeFactory.instance.textNode("timeline"),
            JsonNodeFactory.instance.textNode("news")
        );
        assertThat(dslContext.select(MIRRORS.FEEDS).from(MIRRORS).fetchSingle(MIRRORS.FEEDS))
            .containsExactly("timeline", "news");

        assertThat(request("POST", "/api/destinations", objectNode()
            .put("nodeName", "second-destination-node")).statusCode()).isEqualTo(201);
        ObjectNode secondMirrorRequest = objectNode()
            .put("channelId", 1)
            .put("destinationId", 2);
        secondMirrorRequest.putArray("feeds").add("archive");
        JsonNode secondMirror = json(request("POST", "/api/mirrors", secondMirrorRequest));

        HttpResponse<String> mirrors = request("GET", "/api/mirrors", null);
        assertThat(mirrors.statusCode()).isEqualTo(200);
        assertThat(json(mirrors)).containsExactly(mirror, secondMirror);

        assertThat(request("DELETE", "/api/mirrors/1/1", null).statusCode()).isEqualTo(204);
        assertThat(dslContext.fetchCount(MIRRORS)).isOne();
        assertThat(request("DELETE", "/api/mirrors/1/2", null).statusCode()).isEqualTo(204);
        assertThat(dslContext.fetchCount(MIRRORS)).isZero();

        assertThat(request("DELETE", "/api/channels/1", null).statusCode()).isEqualTo(204);
        assertThat(dslContext.fetchCount(CHANNELS)).isZero();
        assertThat(request("DELETE", "/api/destinations/1", null).statusCode()).isEqualTo(204);
        assertThat(request("DELETE", "/api/destinations/2", null).statusCode()).isEqualTo(204);
        assertThat(dslContext.fetchCount(DESTINATIONS)).isZero();
    }

    @Test
    void createChannelDefaultsToRecentHistory() throws Exception {
        HttpResponse<String> response = request("POST", "/api/channels", objectNode()
            .put("sourceType", "TELEGRAM")
            .put("sourceUri", "https://t.me/news"));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(fakeSourceAdapter.resolvedMode).isEqualTo(InitialImportMode.RECENT);
    }

    @Test
    void createChannelCanRequestFullHistory() throws Exception {
        HttpResponse<String> response = request("POST", "/api/channels", objectNode()
            .put("sourceType", "TELEGRAM")
            .put("sourceUri", "https://t.me/news")
            .put("fullHistory", true));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(fakeSourceAdapter.resolvedMode).isEqualTo(InitialImportMode.FULL);
    }

    @Test
    void createChannelRejectsConflictingInitialHistoryOptions() throws Exception {
        HttpResponse<String> response = request("POST", "/api/channels", objectNode()
            .put("sourceType", "TELEGRAM")
            .put("sourceUri", "https://t.me/news")
            .put("fullHistory", true)
            .put("initialImportAfter", "2026-09-01T11:30:00Z"));

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void createChannelCanRequestHistoryAfterTimestamp() throws Exception {
        Instant boundary = Instant.parse("2026-09-01T11:30:00Z");

        HttpResponse<String> response = request("POST", "/api/channels", objectNode()
            .put("sourceType", "TELEGRAM")
            .put("sourceUri", "https://t.me/news")
            .put("initialImportAfter", boundary.toString()));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(fakeSourceAdapter.resolvedMode.name()).isEqualTo("AFTER");
        ChannelSnapshot storedChannel = channelRepository.find(1).orElseThrow();
        assertThat(storedChannel.initialImportMode()).isEqualTo(InitialImportMode.AFTER);
        assertThat(storedChannel.initialImportAfter()).isEqualTo(boundary);
    }

    @Test
    void createChannelTruncatesInitialImportBoundaryToTelegramPrecision() throws Exception {
        HttpResponse<String> response = request("POST", "/api/channels", objectNode()
            .put("sourceType", "TELEGRAM")
            .put("sourceUri", "https://t.me/news")
            .put("initialImportAfter", "2026-09-01T11:30:00.999999999Z"));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(channelRepository.find(1).orElseThrow().initialImportAfter())
            .isEqualTo(Instant.parse("2026-09-01T11:30:00Z"));
    }

    @Test
    void databaseRejectsInitialImportModeBoundaryMismatch() {
        assertThatThrownBy(() -> insertChannel(InitialImportMode.AFTER, null))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertChannel(InitialImportMode.RECENT, Instant.parse("2026-09-01T11:30:00Z")))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void destinationNamesAreExpandedInStorageAndResponses() throws Exception {
        HttpResponse<String> response = request("POST", "/api/destinations", objectNode()
            .put("nodeName", "destination-node"));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json(response).path("nodeName").asText()).isEqualTo("destination-node_0");
        assertThat(dslContext.select(DESTINATIONS.NODE_NAME).from(DESTINATIONS).fetchSingle().value1())
            .isEqualTo("destination-node_0");
        assertThat(json(request("GET", "/api/destinations", null)).path(0).path("nodeName").asText())
            .isEqualTo("destination-node_0");
        assertThat(request("POST", "/api/destinations", objectNode()
            .put("nodeName", "destination-node_0")).statusCode()).isEqualTo(409);
    }

    @Test
    void destinationRejectsInvalidGeneration() throws Exception {
        assertThat(request("POST", "/api/destinations", objectNode()
            .put("nodeName", "destination_invalid")).statusCode()).isEqualTo(400);
        assertThat(dslContext.fetchCount(DESTINATIONS)).isZero();
    }

    @Test
    void destinationLengthLimitAppliesToExpandedName() throws Exception {
        assertThat(request("POST", "/api/destinations", objectNode()
            .put("nodeName", "n".repeat(134))).statusCode()).isEqualTo(400);
        assertThat(dslContext.fetchCount(DESTINATIONS)).isZero();

        HttpResponse<String> response = request("POST", "/api/destinations", objectNode()
            .put("nodeName", "n".repeat(133)));
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json(response).path("nodeName").asText()).isEqualTo("n".repeat(133) + "_0");
    }

    @Test
    void destinationAcceptsLongPaddedGenerationWhenExpandedNameFits() throws Exception {
        HttpResponse<String> response = request("POST", "/api/destinations", objectNode()
            .put("nodeName", "destination_" + "0".repeat(135) + "7"));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json(response).path("nodeName").asText()).isEqualTo("destination_7");
        assertThat(dslContext.select(DESTINATIONS.NODE_NAME).from(DESTINATIONS).fetchSingle().value1())
            .isEqualTo("destination_7");
    }

    @Test
    void destinationCanDisableProfileSynchronization() throws Exception {
        HttpResponse<String> response = request("POST", "/api/destinations", objectNode()
            .put("nodeName", "multi-channel-node")
            .put("profileSync", false));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json(response).path("profileSync").asBoolean()).isFalse();
        assertThat(dslContext.select(DESTINATIONS.PROFILE_SYNC).from(DESTINATIONS).fetchSingle().value1()).isFalse();
    }

    @Test
    void reportsConflictsAndMissingResources() throws Exception {
        ObjectNode channelRequest = objectNode()
            .put("sourceType", "TELEGRAM")
            .put("sourceUri", "https://t.me/input");
        assertThat(request("POST", "/api/channels", channelRequest).statusCode()).isEqualTo(201);
        assertThat(request("POST", "/api/channels", channelRequest).statusCode()).isEqualTo(409);

        ObjectNode destinationRequest = objectNode()
            .put("nodeName", "destination-node");
        assertThat(request("POST", "/api/destinations", destinationRequest).statusCode()).isEqualTo(201);
        assertThat(request("POST", "/api/destinations", destinationRequest).statusCode()).isEqualTo(409);

        ObjectNode mirrorRequest = objectNode()
            .put("channelId", 1)
            .put("destinationId", 1);
        mirrorRequest.putArray("feeds").add("timeline");
        assertThat(request("POST", "/api/mirrors", mirrorRequest).statusCode()).isEqualTo(201);
        assertThat(request("POST", "/api/mirrors", mirrorRequest).statusCode()).isEqualTo(409);

        ObjectNode missingMirrorRequest = objectNode()
            .put("channelId", 999)
            .put("destinationId", 1);
        missingMirrorRequest.putArray("feeds").add("timeline");
        assertThat(request("POST", "/api/mirrors", missingMirrorRequest).statusCode()).isEqualTo(404);
        assertThat(request("DELETE", "/api/mirrors/999/1", null).statusCode()).isEqualTo(404);
        assertThat(request("DELETE", "/api/channels/999", null).statusCode()).isEqualTo(404);
        assertThat(request("DELETE", "/api/destinations/999", null).statusCode()).isEqualTo(404);
    }

    @Test
    void rejectsInvalidConfiguration() throws Exception {
        assertThat(request("POST", "/api/channels", objectNode()
            .put("sourceType", "TELEGRAM")
            .put("sourceUri", "not a URI")).statusCode()).isEqualTo(400);
        assertThat(request("POST", "/api/destinations", objectNode()
            .put("nodeName", "")).statusCode()).isEqualTo(400);

        ObjectNode mirrorRequest = objectNode()
            .put("channelId", 1)
            .put("destinationId", 1);
        mirrorRequest.putArray("feeds");
        assertThat(request("POST", "/api/mirrors", mirrorRequest).statusCode()).isEqualTo(400);
    }

    @Test
    void rejectsAdministrationRequestsFromNonLoopbackPeers() {
        LocalAdministrationInterceptor interceptor = new LocalAdministrationInterceptor();
        MockHttpServletRequest localRequest = new MockHttpServletRequest();
        localRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse localResponse = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(localRequest, localResponse, new Object())).isTrue();

        MockHttpServletRequest remoteRequest = new MockHttpServletRequest();
        remoteRequest.setRemoteAddr("192.0.2.1");
        MockHttpServletResponse remoteResponse = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(remoteRequest, remoteResponse, new Object())).isFalse();
        assertThat(remoteResponse.getStatus()).isEqualTo(403);
    }

    private HttpResponse<String> request(String method, String path, JsonNode body)
        throws IOException, InterruptedException {
        HttpRequest.BodyPublisher publisher = body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body.toString());
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + path))
            .method(method, publisher);
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void insertChannel(InitialImportMode initialImportMode, Instant initialImportAfter) {
        dslContext.insertInto(CHANNELS)
            .set(CHANNELS.SOURCE_TYPE, org.moera.importer.persistence.jooq.enums.SourceType.TELEGRAM)
            .set(CHANNELS.SOURCE_URI, "https://t.me/news")
            .set(CHANNELS.ACCESS_STATE, org.moera.importer.persistence.jooq.enums.AccessState.UNKNOWN)
            .set(CHANNELS.SUBSCRIPTION_STATE, org.moera.importer.persistence.jooq.enums.SubscriptionState.INACTIVE)
            .set(CHANNELS.INITIAL_IMPORT_MODE,
                org.moera.importer.persistence.jooq.enums.InitialImportMode.valueOf(initialImportMode.name()))
            .set(CHANNELS.INITIAL_IMPORT_AFTER,
                initialImportAfter != null ? initialImportAfter.atOffset(java.time.ZoneOffset.UTC) : null)
            .execute();
    }

    private JsonNode json(HttpResponse<String> response) throws IOException {
        return objectMapper.readTree(response.body());
    }

    private static ObjectNode objectNode() {
        return JsonNodeFactory.instance.objectNode();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {

        @Bean
        FakeSourceAdapter fakeSourceAdapter() {
            return new FakeSourceAdapter();
        }

    }

    static class FakeSourceAdapter implements SourceAdapter {

        private boolean resolvedOutsideTransaction;
        private boolean resolvedOnVirtualThread;
        private InitialImportMode resolvedMode;

        void reset() {
            resolvedOutsideTransaction = false;
            resolvedOnVirtualThread = false;
            resolvedMode = null;
        }

        @Override
        public SourceType type() {
            return SourceType.TELEGRAM;
        }

        @Override
        public ChannelResolution resolveChannel(URI sourceUri, InitialImportMode initialImportMode) {
            if (!"https".equals(sourceUri.getScheme())) {
                throw new IllegalArgumentException("invalid source URI");
            }
            resolvedOutsideTransaction = !TransactionSynchronizationManager.isActualTransactionActive();
            resolvedOnVirtualThread = Thread.currentThread().isVirtual();
            resolvedMode = initialImportMode;
            return new ChannelResolution(
                "telegram-42",
                URI.create("https://t.me/canonical"),
                objectNode().put("adapterState", "kept-separate"),
                AccessState.GRANTED,
                objectNode(),
                SubscriptionState.INACTIVE,
                objectNode(),
                new ChannelMetadata("Canonical channel", "<p>Description</p>", "canonical", null)
            );
        }

        @Override
        public Duration groupingThreshold() {
            return Duration.ZERO;
        }

        @Override
        public Duration settlingDelay() {
            return Duration.ZERO;
        }

        @Override
        public Duration pollingInterval(ChannelSnapshot channel) {
            return Duration.ZERO;
        }

        @Override
        public GroupingRelation groupingRelation(SourceMessage previous, SourceMessage next) {
            return GroupingRelation.UNKNOWN;
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
        public void downloadMedia(ChannelSnapshot channel, MediaSnapshot media, java.nio.file.Path destination) {
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
