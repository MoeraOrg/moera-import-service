package org.moera.importer.moera;

import java.util.Locale;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

import org.moera.importer.model.Destination;
import org.moera.lib.http.OkHttpTransport;
import org.moera.lib.jsonrpc.OkHttpJsonRpcFetcher;
import org.moera.lib.naming.MoeraNaming;
import org.moera.lib.naming.MoeraNamingConnectionException;
import org.moera.lib.node.MoeraNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MoeraClientFactory {

    private static final String USER_AGENT = "moera-import-service";

    @Inject
    private MoeraCredentialStore moeraCredentialStore;

    @Value("${moera.naming-server:main}")
    private String configuredNamingServer = "main";

    private MoeraNamingServer moeraNamingServer = MoeraNamingServer.MAIN;

    @PostConstruct
    public void initialize() {
        try {
            moeraNamingServer = MoeraNamingServer.valueOf(configuredNamingServer.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new MoeraConfigurationException("Unsupported Moera naming server", e);
        }
    }

    public MoeraNode create(Destination destination) {
        String nodeUrl = resolve(destination.nodeName());
        String credentialReference = destination.config().path("credentialRef").textValue();
        String token = moeraCredentialStore.resolve(credentialReference);
        MoeraNode moeraNode = new MoeraNode(new OkHttpTransport(), nodeUrl);
        moeraNode.token(token);
        moeraNode.authAdmin();
        moeraNode.userAgent(USER_AGENT);
        return moeraNode;
    }

    private String resolve(String nodeName) {
        try {
            String nodeUrl = MoeraNaming.resolve(
                nodeName, new OkHttpJsonRpcFetcher(namingServerUrl())
            );
            if (nodeUrl == null || nodeUrl.isBlank()) {
                throw new MoeraConfigurationException("Moera node name cannot be resolved");
            }
            return nodeUrl;
        } catch (MoeraNamingConnectionException e) {
            throw new MoeraTemporaryException("Cannot connect to Moera naming service", e);
        } catch (RuntimeException e) {
            throw new MoeraConfigurationException("Cannot resolve Moera node name", e);
        }
    }

    private String namingServerUrl() {
        return switch (moeraNamingServer) {
            case MAIN -> MoeraNaming.MAIN_NAMING_SERVER;
            case DEVELOPMENT -> MoeraNaming.DEV_NAMING_SERVER;
        };
    }

}
