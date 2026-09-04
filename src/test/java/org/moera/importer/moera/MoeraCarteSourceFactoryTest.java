package org.moera.importer.moera;

import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.moera.importer.config.MoeraAuthenticationProperties;
import org.moera.lib.crypto.CryptoUtil;
import org.moera.lib.crypto.Fingerprint;
import org.moera.lib.crypto.RestoredFingerprint;
import org.moera.lib.node.Fingerprints;
import org.moera.lib.node.carte.Carte;
import org.moera.lib.node.carte.CarteSource;
import org.moera.lib.node.types.Scope;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class MoeraCarteSourceFactoryTest {

    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final long ADMIN_SCOPE = Scope.of(
        Scope.VIEW_CONTENT,
        Scope.ADD_POST,
        Scope.UPDATE_POST,
        Scope.DELETE_OWN_CONTENT,
        Scope.VIEW_FEEDS,
        Scope.UPDATE_FEEDS,
        Scope.VIEW_PROFILE,
        Scope.UPDATE_PROFILE,
        Scope.VIEW_SETTINGS,
        Scope.UPLOAD_PUBLIC_MEDIA,
        Scope.UPLOAD_PRIVATE_MEDIA
    );

    @Test
    void createsFreshTargetedCarteWithImporterAdministrativeScope() throws Exception {
        MoeraAuthenticationProperties properties = new MoeraAuthenticationProperties();
        properties.setSigningKey("0".repeat(63) + "1");
        properties.setAddresses(List.of("192.0.2.10", "2001:db8::10"));
        ReflectionTestUtils.invokeMethod(properties, "validate");
        MoeraCarteSourceFactory factory = new MoeraCarteSourceFactory();
        ReflectionTestUtils.setField(factory, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(factory, "moeraAuthenticationProperties", properties);

        CarteSource source = factory.create("destination_2");
        String firstCarte = source.getCarte();
        String secondCarte = source.getCarte();
        Fingerprint fingerprint = restore(firstCarte);

        assertThat(secondCarte).isNotEqualTo(firstCarte);
        assertThat(fingerprint.get("owner_name")).isEqualTo("importer_0");
        assertThat(fingerprint.get("addresses"))
            .asList()
            .containsExactly(
                InetAddress.getByName("192.0.2.10"),
                InetAddress.getByName("2001:db8::10")
            );
        assertThat(fingerprint.get("beginning")).isEqualTo(Timestamp.from(NOW));
        assertThat(fingerprint.get("deadline")).isEqualTo(Timestamp.from(Carte.getDeadline(NOW)));
        assertThat(fingerprint.get("node_name")).isEqualTo("destination_2");
        assertThat(fingerprint.get("client_scope")).isEqualTo(Scope.NONE.getMask());
        assertThat(fingerprint.get("admin_scope")).isEqualTo(ADMIN_SCOPE);
    }

    private Fingerprint restore(String carte) {
        byte[] bytes = Base64.getUrlDecoder().decode(carte);
        RestoredFingerprint restored = CryptoUtil.restoreFingerprint(
            bytes,
            version -> Fingerprints.getSchema("CARTE", version)
        );
        return restored.fingerprint();
    }

}
