package org.moera.importer.moera;

import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.moera.importer.model.Destination;
import org.moera.importer.config.MoeraAuthenticationProperties;
import org.moera.lib.jsonrpc.JsonRpcRequest;
import org.moera.lib.jsonrpc.JsonRpcResponse;
import org.moera.lib.jsonrpc.OkHttpJsonRpcFetcher;
import org.moera.lib.naming.MoeraNaming;
import org.moera.lib.node.MoeraNode;
import org.moera.lib.node.NodeAuth;
import org.moera.lib.node.carte.CarteSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class MoeraClientFactoryTest {

    @Test
    void reusesNodeTransportAcrossClients() {
        MoeraCarteSourceFactory moeraCarteSourceFactory = mock(MoeraCarteSourceFactory.class);
        when(moeraCarteSourceFactory.create("alice")).thenReturn(() -> "signed-carte");
        MoeraClientFactory factory = factory(moeraCarteSourceFactory);

        try (MockedStatic<MoeraNaming> naming = mockStatic(MoeraNaming.class)) {
            naming.when(() -> MoeraNaming.resolve(any(), any())).thenReturn("https://node.example");

            MoeraNode first = factory.create(destination());
            MoeraNode second = factory.create(destination());

            assertThat(ReflectionTestUtils.getField(first, "transport"))
                .isSameAs(ReflectionTestUtils.getField(second, "transport"));
        }
    }

    @Test
    void reusesNamingFetcherAcrossResolutions() {
        MoeraCarteSourceFactory moeraCarteSourceFactory = mock(MoeraCarteSourceFactory.class);
        when(moeraCarteSourceFactory.create("alice")).thenReturn(() -> "signed-carte");
        MoeraClientFactory factory = factory(moeraCarteSourceFactory);
        List<Function<JsonRpcRequest, JsonRpcResponse>> fetchers = new ArrayList<>();

        try (MockedStatic<MoeraNaming> naming = mockStatic(MoeraNaming.class)) {
            naming.when(() -> MoeraNaming.resolve(any(), any())).thenAnswer(invocation -> {
                fetchers.add(invocation.getArgument(1));
                return "https://node.example";
            });

            factory.create(destination());
            factory.create(destination());

            assertThat(fetchers).hasSize(2);
            assertThat(fetchers.get(0)).isSameAs(fetchers.get(1));
        }
    }

    @Test
    void resolvesNodeEveryTimeAndCreatesAuthenticatedClient() {
        MoeraCarteSourceFactory moeraCarteSourceFactory = mock(MoeraCarteSourceFactory.class);
        CarteSource carteSource = () -> "signed-carte";
        when(moeraCarteSourceFactory.create("alice")).thenReturn(carteSource);
        MoeraClientFactory factory = factory(moeraCarteSourceFactory);
        Instant now = Instant.parse("2026-08-27T12:00:00Z");
        Destination destination = new Destination(1, "alice", now, now);

        try (MockedStatic<MoeraNaming> naming = mockStatic(MoeraNaming.class)) {
            naming.when(() -> MoeraNaming.resolve(any(), any()))
                .thenReturn("https://first.example", "https://second.example");

            MoeraNode first = factory.create(destination);
            MoeraNode second = factory.create(destination);

            assertThat(first.getRoot()).isEqualTo("https://first.example/moera");
            assertThat(second.getRoot()).isEqualTo("https://second.example/moera");
            assertThat(ReflectionTestUtils.getField(first, "token")).isNull();
            assertThat(ReflectionTestUtils.getField(first, "carteSource")).isSameAs(carteSource);
            assertThat(ReflectionTestUtils.getField(first, "authMethod")).isEqualTo(NodeAuth.PEER);
            naming.verify(() -> MoeraNaming.resolve(any(), any()), times(2));
        }
    }

    @Test
    void createsClientWithVersionedUserAgent() {
        MoeraCarteSourceFactory moeraCarteSourceFactory = mock(MoeraCarteSourceFactory.class);
        when(moeraCarteSourceFactory.create("alice")).thenReturn(() -> "signed-carte");
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("moera.version=0.1.0")
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(MoeraAuthenticationProperties.class, () -> mock(MoeraAuthenticationProperties.class))
            .withBean(MoeraCarteSourceFactory.class, () -> moeraCarteSourceFactory)
            .withBean(MoeraClientFactory.class);

        try (MockedStatic<MoeraNaming> naming = mockStatic(MoeraNaming.class)) {
            naming.when(() -> MoeraNaming.resolve(any(), any())).thenReturn("https://node.example");

            contextRunner.run(context -> {
                MoeraNode client = context.getBean(MoeraClientFactory.class).create(destination());

                assertThat(ReflectionTestUtils.getField(client, "userAgent"))
                    .isEqualTo("Moera import service/0.1.0");
            });
        }
    }

    @Test
    void resolvesNodeThroughDevelopmentNamingServerWhenConfigured() {
        MoeraCarteSourceFactory moeraCarteSourceFactory = mock(MoeraCarteSourceFactory.class);
        when(moeraCarteSourceFactory.create("alice")).thenReturn(() -> "signed-carte");
        AtomicReference<Function<JsonRpcRequest, JsonRpcResponse>> fetcher = new AtomicReference<>();
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("moera.naming-server=development")
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(MoeraAuthenticationProperties.class, () -> mock(MoeraAuthenticationProperties.class))
            .withBean(MoeraCarteSourceFactory.class, () -> moeraCarteSourceFactory)
            .withBean(MoeraClientFactory.class);

        try (MockedStatic<MoeraNaming> naming = mockStatic(MoeraNaming.class)) {
            naming.when(() -> MoeraNaming.resolve(any(), any())).thenAnswer(invocation -> {
                fetcher.set(invocation.getArgument(1));
                return "https://node.example";
            });

            contextRunner.run(context -> context.getBean(MoeraClientFactory.class).create(destination()));

            assertThat(fetcher.get()).isInstanceOf(OkHttpJsonRpcFetcher.class);
            assertThat(ReflectionTestUtils.getField(fetcher.get(), "url"))
                .isEqualTo(MoeraNaming.DEV_NAMING_SERVER);
        }
    }

    @Test
    void rejectsUnknownNamingServerAtStartup() {
        MoeraCarteSourceFactory moeraCarteSourceFactory = mock(MoeraCarteSourceFactory.class);
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("moera.naming-server=private")
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(MoeraAuthenticationProperties.class, () -> mock(MoeraAuthenticationProperties.class))
            .withBean(MoeraCarteSourceFactory.class, () -> moeraCarteSourceFactory)
            .withBean(MoeraClientFactory.class);

        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasCauseInstanceOf(MoeraConfigurationException.class);
        });
    }

    private Destination destination() {
        Instant now = Instant.parse("2026-08-27T12:00:00Z");
        return new Destination(1, "alice", now, now);
    }

    private MoeraClientFactory factory(MoeraCarteSourceFactory moeraCarteSourceFactory) {
        MoeraClientFactory factory = new MoeraClientFactory();
        ReflectionTestUtils.setField(factory, "moeraCarteSourceFactory", moeraCarteSourceFactory);
        factory.initialize();
        return factory;
    }

}
