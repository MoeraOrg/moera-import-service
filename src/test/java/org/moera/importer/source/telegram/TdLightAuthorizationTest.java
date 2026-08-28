package org.moera.importer.source.telegram;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import it.tdlight.client.APIToken;
import it.tdlight.client.AuthenticationData;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.InputParameter;
import it.tdlight.client.ParameterInfo;
import it.tdlight.client.TDLibSettings;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TdLightAuthorizationTest {

    @Test
    void doesNotLoadChatsWhenAuthorizationBecomesReady() {
        List<TdApi.Function<?>> requests = new ArrayList<>();
        TdLightAuthorization authorization = new TdLightAuthorization(settings(), false, requests::add);

        authorization.handle(new TdApi.AuthorizationStateReady());

        assertThat(authorization.isReady()).isTrue();
        assertThat(requests).noneMatch(TdApi.LoadChats.class::isInstance);
    }

    @Test
    void configuresTdlibWithoutLoadingChats() {
        List<TdApi.Function<?>> requests = new ArrayList<>();
        TDLibSettings settings = settings();
        settings.setDatabaseDirectoryPath(Path.of("session"));
        settings.setDownloadedFilesDirectoryPath(Path.of("downloads"));
        TdLightAuthorization authorization = new TdLightAuthorization(settings, false, requests::add);

        authorization.handle(new TdApi.AuthorizationStateWaitTdlibParameters());

        assertThat(requests).singleElement().isInstanceOfSatisfying(TdApi.SetTdlibParameters.class, request -> {
            assertThat(request.apiId).isEqualTo(1);
            assertThat(request.apiHash).isEqualTo("hash");
            assertThat(request.databaseDirectory).isEqualTo("session");
            assertThat(request.filesDirectory).isEqualTo("downloads");
        });
        assertThat(requests).noneMatch(TdApi.LoadChats.class::isInstance);
    }

    @Test
    void rejectsMissingSessionWithoutPromptingInDaemonMode() {
        List<TdApi.Function<?>> requests = new ArrayList<>();
        TdLightAuthorization authorization = new TdLightAuthorization(settings(), false, requests::add);

        authorization.handle(new TdApi.AuthorizationStateWaitPhoneNumber());

        assertThatThrownBy(() -> authorization.ready().join())
            .hasRootCauseMessage("No usable saved Telegram session; run with TELEGRAM_LOGIN=true once");
        assertThat(requests).isEmpty();
    }

    @Test
    void submitsPhoneNumberDuringInteractiveLogin() {
        List<TdApi.Function<?>> requests = new ArrayList<>();
        AuthenticationData authenticationData = AuthenticationSupplier.user("+123456789").get().join();
        TdLightLoginPrompt prompt = new StubPrompt(authenticationData);
        TdLightAuthorization authorization = new TdLightAuthorization(settings(), true, requests::add, prompt);

        authorization.handle(new TdApi.AuthorizationStateWaitPhoneNumber());

        assertThat(requests).singleElement().isInstanceOfSatisfying(
            TdApi.SetAuthenticationPhoneNumber.class,
            request -> assertThat(request.phoneNumber).isEqualTo("+123456789")
        );
    }

    @Test
    void submitsInteractiveVerificationCode() {
        List<TdApi.Function<?>> requests = new ArrayList<>();
        TdLightLoginPrompt prompt = mock(TdLightLoginPrompt.class);
        when(prompt.ask(eq(InputParameter.ASK_CODE), any()))
            .thenReturn(CompletableFuture.completedFuture("12345"));
        TdLightAuthorization authorization = new TdLightAuthorization(settings(), true, requests::add, prompt);
        TdApi.AuthenticationCodeInfo codeInfo = new TdApi.AuthenticationCodeInfo("+123456789", null, null, 60);

        authorization.handle(new TdApi.AuthorizationStateWaitCode(codeInfo));

        assertThat(requests).singleElement().isInstanceOfSatisfying(
            TdApi.CheckAuthenticationCode.class,
            request -> assertThat(request.code).isEqualTo("12345")
        );
    }

    @Test
    void submitsInteractiveTwoFactorPassword() {
        List<TdApi.Function<?>> requests = new ArrayList<>();
        TdLightLoginPrompt prompt = mock(TdLightLoginPrompt.class);
        when(prompt.ask(eq(InputParameter.ASK_PASSWORD), any()))
            .thenReturn(CompletableFuture.completedFuture("password"));
        TdLightAuthorization authorization = new TdLightAuthorization(settings(), true, requests::add, prompt);

        authorization.handle(new TdApi.AuthorizationStateWaitPassword("hint", true, false, "e***@example.com"));

        assertThat(requests).singleElement().isInstanceOfSatisfying(
            TdApi.CheckAuthenticationPassword.class,
            request -> assertThat(request.password).isEqualTo("password")
        );
    }

    @Test
    void submitsInteractiveEmailAddressAndCode() {
        List<TdApi.Function<?>> requests = new ArrayList<>();
        TdLightLoginPrompt prompt = mock(TdLightLoginPrompt.class);
        when(prompt.ask(eq(InputParameter.ASK_EMAIL_ADDRESS), any()))
            .thenReturn(CompletableFuture.completedFuture("person@example.com"));
        when(prompt.ask(eq(InputParameter.ASK_EMAIL_CODE), any()))
            .thenReturn(CompletableFuture.completedFuture("654321"));
        TdLightAuthorization authorization = new TdLightAuthorization(settings(), true, requests::add, prompt);

        authorization.handle(new TdApi.AuthorizationStateWaitEmailAddress(false, false));
        authorization.handle(new TdApi.AuthorizationStateWaitEmailCode(
            false,
            false,
            new TdApi.EmailAddressAuthenticationCodeInfo("p***@example.com", 6),
            new TdApi.EmailAddressResetStateAvailable(300)
        ));

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0)).isInstanceOfSatisfying(
            TdApi.SetAuthenticationEmailAddress.class,
            request -> assertThat(request.emailAddress).isEqualTo("person@example.com")
        );
        assertThat(requests.get(1)).isInstanceOfSatisfying(
            TdApi.CheckAuthenticationEmailCode.class,
            request -> assertThat(request.code).isInstanceOfSatisfying(
                TdApi.EmailAddressAuthenticationCode.class,
                code -> assertThat(code.code).isEqualTo("654321")
            )
        );
    }

    @Test
    void submitsInteractiveRegistration() {
        List<TdApi.Function<?>> requests = new ArrayList<>();
        TdLightLoginPrompt prompt = mock(TdLightLoginPrompt.class);
        when(prompt.ask(eq(InputParameter.TERMS_OF_SERVICE), any()))
            .thenReturn(CompletableFuture.completedFuture(""));
        when(prompt.ask(eq(InputParameter.ASK_FIRST_NAME), any()))
            .thenReturn(CompletableFuture.completedFuture("Alice"));
        when(prompt.ask(eq(InputParameter.ASK_LAST_NAME), any()))
            .thenReturn(CompletableFuture.completedFuture("Example"));
        TdLightAuthorization authorization = new TdLightAuthorization(settings(), true, requests::add, prompt);
        TdApi.TermsOfService terms = new TdApi.TermsOfService(new TdApi.FormattedText("terms", null), 0, false);

        authorization.handle(new TdApi.AuthorizationStateWaitRegistration(terms));

        assertThat(requests).singleElement().isInstanceOfSatisfying(TdApi.RegisterUser.class, request -> {
            assertThat(request.firstName).isEqualTo("Alice");
            assertThat(request.lastName).isEqualTo("Example");
        });
    }

    private TDLibSettings settings() {
        return TDLibSettings.create(new APIToken(1, "hash"));
    }

    private static class StubPrompt implements TdLightLoginPrompt {

        private final AuthenticationData authenticationData;

        StubPrompt(AuthenticationData authenticationData) {
            this.authenticationData = authenticationData;
        }

        @Override
        public CompletableFuture<AuthenticationData> authenticationData() {
            return CompletableFuture.completedFuture(authenticationData);
        }

        @Override
        public CompletableFuture<String> ask(InputParameter parameter, ParameterInfo parameterInfo) {
            return CompletableFuture.failedFuture(new AssertionError("Unexpected prompt: " + parameter));
        }

    }

}
