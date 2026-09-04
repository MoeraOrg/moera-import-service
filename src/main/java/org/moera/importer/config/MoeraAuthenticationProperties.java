package org.moera.importer.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.PrivateKey;
import java.security.Security;
import java.security.interfaces.ECPrivateKey;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import jakarta.annotation.PostConstruct;

import org.moera.lib.Rules;
import org.moera.lib.crypto.CryptoUtil;
import org.moera.lib.naming.NodeName;
import org.moera.lib.naming.NodeNameParsingException;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

@Component
@ConfigurationProperties("moera.authentication")
public class MoeraAuthenticationProperties {

    private static final String NAME_ACTION = "Set MOERA_AUTHENTICATION_NAME to the registered importer name with its "
        + "generation suffix (for example, importer_0).";
    private static final String SIGNING_KEY_ACTION = "Set MOERA_AUTHENTICATION_SIGNING_KEY to a 32-byte private "
        + "signing key encoded as hexadecimal.";
    private static final String ADDRESSES_ACTION = "Set MOERA_AUTHENTICATION_ADDRESSES to one or more comma-separated "
        + "public IP addresses or hostnames.";

    private String name = "importer_0";
    private String signingKey;
    private List<String> addresses = List.of();
    private PrivateKey privateSigningKey;
    private List<InetAddress> resolvedAddresses = List.of();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSigningKey() {
        return signingKey;
    }

    public void setSigningKey(String signingKey) {
        this.signingKey = signingKey;
    }

    public List<String> getAddresses() {
        return addresses;
    }

    public void setAddresses(List<String> addresses) {
        this.addresses = addresses;
    }

    public PrivateKey getPrivateSigningKey() {
        return privateSigningKey;
    }

    public void setPrivateSigningKey(PrivateKey privateSigningKey) {
        this.privateSigningKey = privateSigningKey;
    }

    public List<InetAddress> getResolvedAddresses() {
        return resolvedAddresses;
    }

    public void setResolvedAddresses(List<InetAddress> resolvedAddresses) {
        this.resolvedAddresses = resolvedAddresses;
    }

    @PostConstruct
    void validate() {
        registerCryptoProvider();
        validateName();
        privateSigningKey = parseSigningKey();
        resolvedAddresses = resolveAddresses();
    }

    private void registerCryptoProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private void validateName() {
        if (ObjectUtils.isEmpty(name)) {
            throw invalidName();
        }

        NodeName nodeName;
        try {
            nodeName = NodeName.parse(name);
        } catch (NodeNameParsingException e) {
            throw invalidName(e);
        }
        if (!Rules.isNameValid(nodeName.getName()) || nodeName.getGeneration() < 0 || name.length() > 135) {
            throw invalidName();
        }
        if (!NodeName.expand(name).equals(name)) {
            throw new MoeraAuthenticationConfigurationException(
                "MOERA_AUTHENTICATION_NAME must include its generation suffix (for example, importer_0).",
                NAME_ACTION
            );
        }
    }

    private MoeraAuthenticationConfigurationException invalidName() {
        return new MoeraAuthenticationConfigurationException(
            "MOERA_AUTHENTICATION_NAME must be a valid Moera node name.",
            NAME_ACTION
        );
    }

    private MoeraAuthenticationConfigurationException invalidName(Throwable cause) {
        return new MoeraAuthenticationConfigurationException(
            "MOERA_AUTHENTICATION_NAME must be a valid Moera node name.",
            NAME_ACTION,
            cause
        );
    }

    private PrivateKey parseSigningKey() {
        if (ObjectUtils.isEmpty(signingKey) || signingKey.length() != Rules.PRIVATE_KEY_LENGTH * 2) {
            throw invalidKeyLength();
        }

        byte[] rawKey;
        try {
            rawKey = HexFormat.of().parseHex(signingKey);
        } catch (IllegalArgumentException e) {
            throw new MoeraAuthenticationConfigurationException(
                "MOERA_AUTHENTICATION_SIGNING_KEY must contain only hexadecimal characters.",
                SIGNING_KEY_ACTION
            );
        }

        try {
            ECPrivateKey privateKey = CryptoUtil.rawToPrivateKey(rawKey);
            CryptoUtil.privateToPublicKey(privateKey);
            return privateKey;
        } catch (RuntimeException e) {
            throw new MoeraAuthenticationConfigurationException(
                "MOERA_AUTHENTICATION_SIGNING_KEY is not a valid private signing key.",
                SIGNING_KEY_ACTION,
                e
            );
        }
    }

    private MoeraAuthenticationConfigurationException invalidKeyLength() {
        int length = ObjectUtils.isEmpty(signingKey) ? 0 : signingKey.length();
        return new MoeraAuthenticationConfigurationException(
            "MOERA_AUTHENTICATION_SIGNING_KEY must contain exactly 64 hexadecimal characters; got " + length + ".",
            SIGNING_KEY_ACTION
        );
    }

    private List<InetAddress> resolveAddresses() {
        if (ObjectUtils.isEmpty(addresses)) {
            throw new MoeraAuthenticationConfigurationException(
                "MOERA_AUTHENTICATION_ADDRESSES must contain at least one IP address or hostname.",
                ADDRESSES_ACTION
            );
        }

        List<InetAddress> resolved = new ArrayList<>();
        for (String address : addresses) {
            if (ObjectUtils.isEmpty(address) || address.isBlank()) {
                throw new MoeraAuthenticationConfigurationException(
                    "MOERA_AUTHENTICATION_ADDRESSES contains an empty address.",
                    ADDRESSES_ACTION
                );
            }
            try {
                resolved.addAll(List.of(InetAddress.getAllByName(address)));
            } catch (UnknownHostException e) {
                throw new MoeraAuthenticationConfigurationException(
                    "MOERA_AUTHENTICATION_ADDRESSES contains an address that cannot be resolved: " + address + ".",
                    ADDRESSES_ACTION,
                    e
                );
            }
        }
        return List.copyOf(resolved);
    }

}
