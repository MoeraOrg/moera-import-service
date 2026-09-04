package org.moera.importer.config;

import java.net.InetAddress;
import java.security.interfaces.ECPrivateKey;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.moera.lib.crypto.CryptoUtil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoeraAuthenticationPropertiesTest {

    private static final String VALID_KEY = "0".repeat(63) + "1";

    @Test
    void defaultsNameAndParsesRequiredIdentityMaterial() {
        MoeraAuthenticationProperties properties = validProperties();

        properties.validate();

        assertThat(properties.getName()).isEqualTo("importer_0");
        assertThat(CryptoUtil.rawPrivateKey((ECPrivateKey) properties.getPrivateSigningKey()))
            .containsExactly(HexFormat.of().parseHex(VALID_KEY));
        assertThat(properties.getResolvedAddresses())
            .extracting(InetAddress::getHostAddress)
            .containsExactly("127.0.0.1", "0:0:0:0:0:0:0:1");
    }

    @Test
    void rejectsNameWithoutGeneration() {
        MoeraAuthenticationProperties properties = validProperties();
        properties.setName("importer");

        assertThatThrownBy(properties::validate)
            .isInstanceOf(MoeraAuthenticationConfigurationException.class)
            .hasMessage("MOERA_AUTHENTICATION_NAME must include its generation suffix (for example, importer_0).");
    }

    @Test
    void rejectsInvalidName() {
        MoeraAuthenticationProperties properties = validProperties();
        properties.setName("bad_name_extra");

        assertThatThrownBy(properties::validate)
            .isInstanceOf(MoeraAuthenticationConfigurationException.class)
            .hasMessage("MOERA_AUTHENTICATION_NAME must be a valid Moera node name.");
    }

    @Test
    void rejectsMissingAndShortKeys() {
        assertThatThrownBy(new MoeraAuthenticationProperties()::validate)
            .isInstanceOf(MoeraAuthenticationConfigurationException.class)
            .hasMessage("MOERA_AUTHENTICATION_SIGNING_KEY must contain exactly 64 hexadecimal characters; got 0.");

        MoeraAuthenticationProperties shortKey = validProperties();
        shortKey.setSigningKey("0".repeat(63));
        assertThatThrownBy(shortKey::validate)
            .isInstanceOf(MoeraAuthenticationConfigurationException.class)
            .hasMessage("MOERA_AUTHENTICATION_SIGNING_KEY must contain exactly 64 hexadecimal characters; got 63.");
    }

    @Test
    void rejectsNonHexKey() {
        MoeraAuthenticationProperties nonHexKey = validProperties();
        nonHexKey.setSigningKey("z".repeat(64));

        assertThatThrownBy(nonHexKey::validate)
            .isInstanceOf(MoeraAuthenticationConfigurationException.class)
            .hasMessage("MOERA_AUTHENTICATION_SIGNING_KEY must contain only hexadecimal characters.");
    }

    @Test
    void rejectsMissingBlankAndUnresolvableAddresses() {
        MoeraAuthenticationProperties missing = validProperties();
        missing.setAddresses(List.of());
        assertThatThrownBy(missing::validate)
            .isInstanceOf(MoeraAuthenticationConfigurationException.class)
            .hasMessage("MOERA_AUTHENTICATION_ADDRESSES must contain at least one IP address or hostname.");

        MoeraAuthenticationProperties blank = validProperties();
        blank.setAddresses(List.of(""));
        assertThatThrownBy(blank::validate)
            .isInstanceOf(MoeraAuthenticationConfigurationException.class)
            .hasMessage("MOERA_AUTHENTICATION_ADDRESSES contains an empty address.");

        MoeraAuthenticationProperties unresolvable = validProperties();
        unresolvable.setAddresses(List.of("256.256.256.256"));
        assertThatThrownBy(unresolvable::validate)
            .isInstanceOf(MoeraAuthenticationConfigurationException.class)
            .hasMessage("MOERA_AUTHENTICATION_ADDRESSES contains an address that cannot be resolved: "
                + "256.256.256.256.");
    }

    private MoeraAuthenticationProperties validProperties() {
        MoeraAuthenticationProperties properties = new MoeraAuthenticationProperties();
        properties.setSigningKey(VALID_KEY);
        properties.setAddresses(List.of("127.0.0.1", "::1"));
        return properties;
    }

}
