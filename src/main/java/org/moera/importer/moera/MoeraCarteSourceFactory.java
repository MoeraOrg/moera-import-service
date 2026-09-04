package org.moera.importer.moera;

import java.time.Clock;

import jakarta.inject.Inject;

import org.moera.importer.config.MoeraAuthenticationProperties;
import org.moera.lib.node.carte.Carte;
import org.moera.lib.node.carte.CarteSource;
import org.moera.lib.node.types.Scope;
import org.springframework.stereotype.Component;

@Component
public class MoeraCarteSourceFactory {

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

    @Inject
    private Clock clock;

    @Inject
    private MoeraAuthenticationProperties moeraAuthenticationProperties;

    public CarteSource create(String targetNodeName) {
        return () -> Carte.generate(
            moeraAuthenticationProperties.getName(),
            moeraAuthenticationProperties.getResolvedAddresses(),
            clock.instant(),
            moeraAuthenticationProperties.getPrivateSigningKey(),
            targetNodeName,
            Scope.NONE.getMask(),
            ADMIN_SCOPE
        );
    }

}
