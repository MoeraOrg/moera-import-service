package org.moera.importer.source.telegram;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import it.tdlight.client.AuthenticationData;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.InputParameter;
import it.tdlight.client.ParameterInfo;
import it.tdlight.client.ParameterInfoCode;
import it.tdlight.client.ParameterInfoNotifyLink;
import it.tdlight.client.ParameterInfoPasswordHint;
import it.tdlight.client.ParameterInfoTermsOfService;
import it.tdlight.util.ScannerUtils;

final class TdLightConsoleLoginPrompt implements TdLightLoginPrompt {

    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public CompletableFuture<AuthenticationData> authenticationData() {
        return CompletableFuture.supplyAsync(() -> ask("Enter your phone number").trim(), executorService)
            .thenCompose(phoneNumber -> AuthenticationSupplier.user(phoneNumber).get())
            .thenApply(AuthenticationData.class::cast);
    }

    @Override
    public CompletableFuture<String> ask(InputParameter parameter, ParameterInfo parameterInfo) {
        return CompletableFuture.supplyAsync(() -> askNow(parameter, parameterInfo), executorService);
    }

    private String askNow(InputParameter parameter, ParameterInfo parameterInfo) {
        return switch (parameter) {
            case ASK_CODE -> askCode((ParameterInfoCode) parameterInfo);
            case ASK_PASSWORD -> askPassword((ParameterInfoPasswordHint) parameterInfo);
            case ASK_FIRST_NAME -> ask("Enter first name").trim();
            case ASK_LAST_NAME -> ask("Enter last name").trim();
            case ASK_EMAIL_ADDRESS -> ask("Enter authentication email address").trim();
            case ASK_EMAIL_CODE -> ask("Enter authentication email code").trim();
            case NOTIFY_LINK -> showConfirmationLink((ParameterInfoNotifyLink) parameterInfo);
            case TERMS_OF_SERVICE -> acceptTerms((ParameterInfoTermsOfService) parameterInfo);
        };
    }

    private String askCode(ParameterInfoCode parameterInfo) {
        String question = "Enter authentication code"
            + System.lineSeparator() + "\tPhone number: " + parameterInfo.getPhoneNumber()
            + System.lineSeparator() + "\tTimeout: " + parameterInfo.getTimeout() + " seconds";
        return ask(question).trim();
    }

    private String askPassword(ParameterInfoPasswordHint parameterInfo) {
        String question = "Enter your password";
        if (parameterInfo.getHint() != null && !parameterInfo.getHint().isEmpty()) {
            question += System.lineSeparator() + "\tHint: " + parameterInfo.getHint();
        }
        return ask(question);
    }

    private String showConfirmationLink(ParameterInfoNotifyLink parameterInfo) {
        System.out.println("Please confirm this login link on another device: " + parameterInfo.getLink());
        return "";
    }

    private String acceptTerms(ParameterInfoTermsOfService parameterInfo) {
        var terms = parameterInfo.getTermsOfService();
        String text = terms.text == null ? "" : terms.text.text;
        System.out.println("Terms of service:" + System.lineSeparator() + "\t" + text);
        return terms.showPopup ? ask("Press enter to accept") : "";
    }

    private String ask(String question) {
        return Objects.requireNonNull(ScannerUtils.askParameter("login", question));
    }

    @Override
    public void close() {
        executorService.close();
    }

}
