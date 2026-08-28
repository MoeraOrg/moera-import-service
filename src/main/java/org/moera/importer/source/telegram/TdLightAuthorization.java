package org.moera.importer.source.telegram;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import it.tdlight.client.AuthenticationData;
import it.tdlight.client.EmailAddressResetState;
import it.tdlight.client.EmptyParameterInfo;
import it.tdlight.client.InputParameter;
import it.tdlight.client.ParameterInfo;
import it.tdlight.client.ParameterInfoCode;
import it.tdlight.client.ParameterInfoEmailAddress;
import it.tdlight.client.ParameterInfoEmailCode;
import it.tdlight.client.ParameterInfoNotifyLink;
import it.tdlight.client.ParameterInfoPasswordHint;
import it.tdlight.client.ParameterInfoTermsOfService;
import it.tdlight.client.TDLibSettings;
import it.tdlight.jni.TdApi;

final class TdLightAuthorization {

    private final TDLibSettings settings;
    private final boolean interactive;
    private final Consumer<TdApi.Function<?>> requestSender;
    private final TdLightLoginPrompt loginPrompt;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();

    TdLightAuthorization(TDLibSettings settings, boolean interactive,
                         Consumer<TdApi.Function<?>> requestSender) {
        this(settings, interactive, requestSender, null);
    }

    TdLightAuthorization(TDLibSettings settings, boolean interactive,
                         Consumer<TdApi.Function<?>> requestSender, TdLightLoginPrompt loginPrompt) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.interactive = interactive;
        this.requestSender = Objects.requireNonNull(requestSender, "requestSender");
        this.loginPrompt = interactive ? Objects.requireNonNull(loginPrompt, "loginPrompt") : loginPrompt;
    }

    void handle(TdApi.AuthorizationState state) {
        if (state instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
            requestSender.accept(tdlibParameters());
        } else if (state instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            handlePhoneNumber();
        } else if (state instanceof TdApi.AuthorizationStateWaitCode waitCode) {
            handleCode(waitCode);
        } else if (state instanceof TdApi.AuthorizationStateWaitPassword waitPassword) {
            handlePassword(waitPassword);
        } else if (state instanceof TdApi.AuthorizationStateWaitEmailAddress waitEmailAddress) {
            handleEmailAddress(waitEmailAddress);
        } else if (state instanceof TdApi.AuthorizationStateWaitEmailCode waitEmailCode) {
            handleEmailCode(waitEmailCode);
        } else if (state instanceof TdApi.AuthorizationStateWaitRegistration waitRegistration) {
            handleRegistration(waitRegistration);
        } else if (state instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation confirmation) {
            handleOtherDeviceConfirmation(confirmation);
        } else if (state instanceof TdApi.AuthorizationStateReady) {
            ready.complete(null);
        } else if (state instanceof TdApi.AuthorizationStateClosed) {
            ready.completeExceptionally(new IllegalStateException("TDLight client closed before authorization"));
            closed.complete(null);
        }
    }

    private void handlePhoneNumber() {
        if (!interactive) {
            ready.completeExceptionally(new IllegalStateException(
                "No usable saved Telegram session; run with TELEGRAM_LOGIN=true once"
            ));
            return;
        }
        loginPrompt.authenticationData().whenComplete((authenticationData, failure) -> {
            if (failure != null) {
                ready.completeExceptionally(failure);
            } else {
                submitAuthenticationData(authenticationData);
            }
        });
    }

    private void submitAuthenticationData(AuthenticationData authenticationData) {
        if (authenticationData.isBot()) {
            requestSender.accept(new TdApi.CheckAuthenticationBotToken(authenticationData.getBotToken()));
        } else if (authenticationData.isQrCode()) {
            requestSender.accept(new TdApi.RequestQrCodeAuthentication());
        } else {
            TdApi.PhoneNumberAuthenticationSettings phoneSettings =
                new TdApi.PhoneNumberAuthenticationSettings(false, false, false, false, false, null, null);
            requestSender.accept(new TdApi.SetAuthenticationPhoneNumber(
                authenticationData.getUserPhoneNumber(), phoneSettings
            ));
        }
    }

    private void handleCode(TdApi.AuthorizationStateWaitCode state) {
        ParameterInfoCode parameterInfo = new ParameterInfoCode(
            state.codeInfo.phoneNumber,
            state.codeInfo.nextType,
            state.codeInfo.timeout,
            state.codeInfo.type
        );
        ask(InputParameter.ASK_CODE, parameterInfo, TdApi.CheckAuthenticationCode::new);
    }

    private void handlePassword(TdApi.AuthorizationStateWaitPassword state) {
        ParameterInfoPasswordHint parameterInfo = new ParameterInfoPasswordHint(
            state.passwordHint,
            state.hasRecoveryEmailAddress,
            state.recoveryEmailAddressPattern
        );
        ask(InputParameter.ASK_PASSWORD, parameterInfo, TdApi.CheckAuthenticationPassword::new);
    }

    private void handleEmailAddress(TdApi.AuthorizationStateWaitEmailAddress state) {
        ParameterInfoEmailAddress parameterInfo = new ParameterInfoEmailAddress(
            state.allowAppleId, state.allowGoogleId
        );
        ask(InputParameter.ASK_EMAIL_ADDRESS, parameterInfo, TdApi.SetAuthenticationEmailAddress::new);
    }

    private void handleEmailCode(TdApi.AuthorizationStateWaitEmailCode state) {
        String resetStateName = state.emailAddressResetState.getClass().getSimpleName()
            .substring("EmailAddressResetState".length())
            .toUpperCase(Locale.ROOT);
        ParameterInfoEmailCode parameterInfo = new ParameterInfoEmailCode(
            state.allowAppleId,
            state.allowGoogleId,
            state.codeInfo.emailAddressPattern,
            state.codeInfo.length,
            EmailAddressResetState.valueOf(resetStateName)
        );
        ask(
            InputParameter.ASK_EMAIL_CODE,
            parameterInfo,
            code -> new TdApi.CheckAuthenticationEmailCode(new TdApi.EmailAddressAuthenticationCode(code))
        );
    }

    private void handleRegistration(TdApi.AuthorizationStateWaitRegistration state) {
        if (!requireLoginPrompt()) {
            return;
        }
        loginPrompt.ask(InputParameter.TERMS_OF_SERVICE, new ParameterInfoTermsOfService(state.termsOfService))
            .thenCompose(ignored -> loginPrompt.ask(InputParameter.ASK_FIRST_NAME, new EmptyParameterInfo()))
            .thenCompose(firstName -> loginPrompt.ask(InputParameter.ASK_LAST_NAME, new EmptyParameterInfo())
                .thenAccept(lastName -> register(firstName, lastName)))
            .whenComplete((ignored, failure) -> {
                if (failure != null) {
                    ready.completeExceptionally(failure);
                }
            });
    }

    private void handleOtherDeviceConfirmation(TdApi.AuthorizationStateWaitOtherDeviceConfirmation state) {
        if (!requireLoginPrompt()) {
            return;
        }
        loginPrompt.ask(InputParameter.NOTIFY_LINK, new ParameterInfoNotifyLink(state.link))
            .whenComplete((ignored, failure) -> {
                if (failure != null) {
                    ready.completeExceptionally(failure);
                }
            });
    }

    private void register(String firstName, String lastName) {
        if (firstName == null || firstName.isEmpty() || firstName.length() > 64) {
            ready.completeExceptionally(new IllegalArgumentException("Invalid Telegram first name"));
        } else if (lastName == null || lastName.length() > 64) {
            ready.completeExceptionally(new IllegalArgumentException("Invalid Telegram last name"));
        } else {
            requestSender.accept(new TdApi.RegisterUser(firstName, lastName, true));
        }
    }

    private void ask(InputParameter parameter, ParameterInfo parameterInfo,
                     Function<String, TdApi.Function<?>> requestFactory) {
        if (!requireLoginPrompt()) {
            return;
        }
        loginPrompt.ask(parameter, parameterInfo).whenComplete((answer, failure) -> {
            if (failure != null) {
                ready.completeExceptionally(failure);
            } else {
                requestSender.accept(requestFactory.apply(answer));
            }
        });
    }

    private boolean requireLoginPrompt() {
        if (interactive && loginPrompt != null) {
            return true;
        }
        ready.completeExceptionally(new IllegalStateException(
            "No usable saved Telegram session; run with TELEGRAM_LOGIN=true once"
        ));
        return false;
    }

    private TdApi.SetTdlibParameters tdlibParameters() {
        TdApi.SetTdlibParameters parameters = new TdApi.SetTdlibParameters();
        parameters.useTestDc = settings.isUsingTestDatacenter();
        parameters.databaseDirectory = settings.getDatabaseDirectoryPath().toString();
        parameters.filesDirectory = settings.getDownloadedFilesDirectoryPath().toString();
        parameters.useFileDatabase = settings.isFileDatabaseEnabled();
        parameters.useChatInfoDatabase = settings.isChatInfoDatabaseEnabled();
        parameters.useMessageDatabase = settings.isMessageDatabaseEnabled();
        parameters.useSecretChats = false;
        parameters.apiId = settings.getApiToken().getApiID();
        parameters.apiHash = settings.getApiToken().getApiHash();
        parameters.systemLanguageCode = settings.getSystemLanguageCode();
        parameters.deviceModel = settings.getDeviceModel();
        parameters.systemVersion = settings.getSystemVersion();
        parameters.applicationVersion = settings.getApplicationVersion();
        parameters.databaseEncryptionKey = null;
        return parameters;
    }

    boolean isReady() {
        return ready.isDone() && !ready.isCompletedExceptionally();
    }

    CompletableFuture<Void> ready() {
        return ready;
    }

    CompletableFuture<Void> closed() {
        return closed;
    }

    void fail(Throwable failure) {
        ready.completeExceptionally(failure);
    }

}
