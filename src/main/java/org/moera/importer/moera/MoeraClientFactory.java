package org.moera.importer.moera;

import jakarta.inject.Inject;

import org.moera.importer.model.Destination;
import org.moera.lib.http.OkHttpTransport;
import org.moera.lib.jsonrpc.OkHttpJsonRpcFetcher;
import org.moera.lib.naming.MoeraNaming;
import org.moera.lib.naming.MoeraNamingConnectionException;
import org.moera.lib.node.MoeraNode;
import org.springframework.stereotype.Component;

@Component
public class MoeraClientFactory {

    private static final String USER_AGENT = "moera-import-service";

    @Inject
    private MoeraCredentialStore moeraCredentialStore;

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
                nodeName, new OkHttpJsonRpcFetcher(MoeraNaming.MAIN_NAMING_SERVER)
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

}
