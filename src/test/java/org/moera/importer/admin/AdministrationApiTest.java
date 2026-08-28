package org.moera.importer.admin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
import org.moera.importer.persistence.PostgresIntegrationTest;
import org.moera.importer.source.AccessResult;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
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
            .put("nodeName", "destination-node")
            .put("credentialRef", "env:MOERA_ADMIN_TOKEN"));
        assertThat(destinationCreated.statusCode()).isEqualTo(201);
        JsonNode destination = json(destinationCreated);
        assertThat(destination.path("id").asLong()).isEqualTo(1);
        assertThat(destination.path("nodeName").asText()).isEqualTo("destination-node");
        assertThat(destination.path("credentialRef").asText()).isEqualTo("env:MOERA_ADMIN_TOKEN");

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
        ObjectNode expectedConfig = objectNode();
        expectedConfig.set("feeds", mirrorRequest.path("feeds"));
        assertThat(objectMapper.readTree(
            dslContext.select(MIRRORS.CONFIG).from(MIRRORS).fetchSingle(MIRRORS.CONFIG).data()
        )).isEqualTo(expectedConfig);

        assertThat(request("DELETE", "/api/mirrors/1/1", null).statusCode()).isEqualTo(204);
        assertThat(dslContext.fetchCount(MIRRORS)).isZero();

        assertThat(request("DELETE", "/api/channels/1", null).statusCode()).isEqualTo(204);
        assertThat(dslContext.fetchCount(CHANNELS)).isZero();
        assertThat(request("DELETE", "/api/destinations/1", null).statusCode()).isEqualTo(204);
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
    void reportsConflictsAndMissingResources() throws Exception {
        ObjectNode channelRequest = objectNode()
            .put("sourceType", "TELEGRAM")
            .put("sourceUri", "https://t.me/input");
        assertThat(request("POST", "/api/channels", channelRequest).statusCode()).isEqualTo(201);
        assertThat(request("POST", "/api/channels", channelRequest).statusCode()).isEqualTo(409);

        ObjectNode destinationRequest = objectNode()
            .put("nodeName", "destination-node")
            .put("credentialRef", "env:MOERA_ADMIN_TOKEN");
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
            .put("nodeName", "")
            .put("credentialRef", "raw-secret")).statusCode()).isEqualTo(400);

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
                objectNode().put("title", "Canonical channel"),
                AccessState.GRANTED,
                objectNode(),
                SubscriptionState.INACTIVE,
                objectNode()
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
