package org.moera.importer.moera;

import java.time.Instant;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.moera.importer.model.Destination;
import org.moera.lib.naming.MoeraNaming;
import org.moera.lib.node.MoeraNode;
import org.moera.lib.node.NodeAuth;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MoeraClientFactoryTest {

    @Test
    void resolvesNodeEveryTimeAndCreatesAuthenticatedClient() {
        MoeraCredentialStore moeraCredentialStore = mock(MoeraCredentialStore.class);
        when(moeraCredentialStore.resolve("env:MOERA_TOKEN")).thenReturn("secret-token");
        MoeraClientFactory factory = new MoeraClientFactory();
        ReflectionTestUtils.setField(factory, "moeraCredentialStore", moeraCredentialStore);
        var config = JsonNodeFactory.instance.objectNode().put("credentialRef", "env:MOERA_TOKEN");
        Instant now = Instant.parse("2026-08-27T12:00:00Z");
        Destination destination = new Destination(
            1, "alice", config, JsonNodeFactory.instance.objectNode(), now, now
        );

        try (MockedStatic<MoeraNaming> naming = mockStatic(MoeraNaming.class)) {
            naming.when(() -> MoeraNaming.resolve(any(), any()))
                .thenReturn("https://first.example", "https://second.example");

            MoeraNode first = factory.create(destination);
            MoeraNode second = factory.create(destination);

            assertThat(first.getRoot()).isEqualTo("https://first.example/moera");
            assertThat(second.getRoot()).isEqualTo("https://second.example/moera");
            assertThat(ReflectionTestUtils.getField(first, "token")).isEqualTo("secret-token");
            assertThat(ReflectionTestUtils.getField(first, "authMethod")).isEqualTo(NodeAuth.ADMIN);
            naming.verify(() -> MoeraNaming.resolve(any(), any()), times(2));
        }
        verify(moeraCredentialStore, times(2)).resolve("env:MOERA_TOKEN");
    }

}
