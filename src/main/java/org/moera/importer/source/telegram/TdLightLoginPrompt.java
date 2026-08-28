package org.moera.importer.source.telegram;

import java.util.concurrent.CompletableFuture;

import it.tdlight.client.AuthenticationData;
import it.tdlight.client.InputParameter;
import it.tdlight.client.ParameterInfo;

interface TdLightLoginPrompt extends AutoCloseable {

    CompletableFuture<AuthenticationData> authenticationData();

    CompletableFuture<String> ask(InputParameter parameter, ParameterInfo parameterInfo);

    @Override
    default void close() {
    }

}
