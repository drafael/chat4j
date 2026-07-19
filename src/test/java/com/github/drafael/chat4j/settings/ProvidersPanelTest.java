package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.provider.support.CredentialTestSupport;
import com.sun.net.httpserver.HttpServer;
import java.awt.Component;
import java.awt.Container;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Arrays.fill;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProvidersPanelTest {

    @TempDir
    private Path tempDir;

    @BeforeEach
    void setUp() {
        CredentialTestSupport.configureVault(StoragePaths.ofConfigHome(tempDir));
        CredentialResolver.init(emptyMap());
    }

    @AfterEach
    void tearDown() {
        CredentialTestSupport.reset();
    }

    @Test
    @DisplayName("Provider panel configured base URL reports read failures through status path")
    void configuredProviderBaseUrl_whenReadFails_returnsDefaultAndShowsStatusError() throws Exception {
        ProvidersPanel subject = callOnEdt(() -> newPanel(new ThrowingSettingsRepo()));
        try {
            callOnEdt(() -> {
                String baseUrl = subject.configuredProviderBaseUrl(
                        "LM Studio",
                        ProvidersPanel.ProviderInfo.local("http://localhost:1234/v1")
                );

                assertThat(baseUrl).isEqualTo("http://localhost:1234/v1");
                assertThat(subject.statusLabel().isVisible()).isTrue();
                assertThat(subject.statusLabel().getText())
                        .contains("Failed to read setting", "chat4j.provider.lm-studio.baseUrl");
                return null;
            });
        } finally {
            runOnEdt(subject::removeNotify);
            runOnEdt(() -> {
            });
        }
    }

    @Test
    @DisplayName("Provider credential refresh hides stale missing token guidance")
    void refreshProviderCredentialUi_whenTokenWasSaved_hidesMissingTokenInfoPanel() throws Exception {
        ProvidersPanel subject = callOnEdt(() -> newPanel(
                new SettingsRepository(tempDir.resolve("providers.properties"))
        ));
        try {
            JLabel statusLabel = callOnEdt(JLabel::new);
            JPanel missingTokenInfoPanel = callOnEdt(JPanel::new);
            char[] token = "saved-token".toCharArray();
            try {
                CredentialTestSupport.saveToken("OPENAI_API_KEY", token);
            } finally {
                fill(token, '\0');
            }

            runOnEdt(() -> subject.refreshProviderCredentialUi(
                    statusLabel,
                    "OpenAI",
                    ProvidersPanel.ProviderInfo.envVar("OPENAI_API_KEY", "https://api.openai.com/v1"),
                    missingTokenInfoPanel
            ));

            callOnEdt(() -> {
                assertThat(statusLabel.getText()).contains("Saved token configured");
                assertThat(missingTokenInfoPanel.isVisible()).isFalse();
                return null;
            });
        } finally {
            runOnEdt(subject::removeNotify);
            runOnEdt(() -> {
            });
        }
    }

    @Test
    @DisplayName("Local provider status updates when the initial background probe completes")
    void createDetailPanel_whenLocalProviderIsReachable_updatesInitialStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        ProvidersPanel subject = null;
        try {
            String baseUrl = "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort());
            var settingsRepo = new SettingsRepository(tempDir.resolve("local-provider.properties"));
            settingsRepo.put("chat4j.provider.lm-studio.baseUrl", "  %s  ".formatted(baseUrl));
            subject = callOnEdt(() -> newPanel(settingsRepo));
            ProvidersPanel panel = subject;

            awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> containsLabelText(
                    panel,
                    "\u2713 Running at %s".formatted(baseUrl)
            )));
        } finally {
            if (subject != null) {
                ProvidersPanel panel = subject;
                runOnEdt(panel::removeNotify);
                runOnEdt(() -> {
                });
            }
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Successful auth mutation invalidates application state after the panel is removed")
    void configureCopilotAuthAction_whenPanelRemovedAfterMutation_notifiesApplicationWithoutLateUiUpdate() throws Exception {
        var logoutStarted = new CountDownLatch(1);
        var releaseLogout = new CountDownLatch(1);
        var authWorker = new AtomicReference<Thread>();
        CopilotAuthResolver copilotAuthResolver = new CopilotAuthResolver(
                tempDir.resolve("lifecycle-copilot-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        ) {
            @Override
            public CopilotAuthStatus resolveStatus() {
                return CopilotAuthStatus.authorized("Authorized", "Chat4J OAuth");
            }

            @Override
            public boolean isOAuthClientConfigured() {
                return true;
            }

            @Override
            public CopilotAuthActionResult logout(BooleanSupplier cancellationRequested) {
                authWorker.set(Thread.currentThread());
                logoutStarted.countDown();
                boolean interrupted = false;
                while (releaseLogout.getCount() > 0) {
                    try {
                        releaseLogout.await();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return CopilotAuthActionResult.success("Logged out.");
            }
        };
        var notifications = new AtomicInteger();
        var notified = new CountDownLatch(1);
        SettingsCredentialChangeListener listener = new SettingsCredentialChangeListener() {
            @Override
            public void credentialChanged(String canonicalTokenId) {
            }

            @Override
            public void providerAuthChanged(String providerName) {
                if ("GitHub Copilot".equals(providerName)) {
                    notifications.incrementAndGet();
                    notified.countDown();
                }
            }
        };
        ProvidersPanel subject = callOnEdt(() -> new ProvidersPanel(
                new SettingsRepository(tempDir.resolve("auth-lifecycle.properties")),
                new ApiTokenFieldRegistry(),
                listener,
                copilotAuthResolver,
                new CodexAuthResolver(
                        tempDir.resolve("lifecycle-codex-home"),
                        emptyMap(),
                        HttpClient.newHttpClient()
                )
        ));

        try {
            runOnEdt(subject::addNotify);
            awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> findButton(subject, "Log out") != null));
            JButton logoutButton = callOnEdt(() -> findButton(subject, "Log out"));
            assertThat(logoutButton).isNotNull();
            runOnEdt(logoutButton::doClick);
            assertThat(logoutStarted.await(2, TimeUnit.SECONDS)).isTrue();
            runOnEdt(subject::removeNotify);
            releaseLogout.countDown();
            assertThat(notified.await(2, TimeUnit.SECONDS)).isTrue();
            runOnEdt(() -> {
            });

            assertThat(notifications).hasValue(1);
            assertThat(callOnEdt(() -> findButton(subject, "Working...") != null)).isTrue();
        } finally {
            releaseLogout.countDown();
            runOnEdt(subject::removeNotify);
            Thread worker = authWorker.get();
            if (worker != null) {
                worker.interrupt();
                worker.join(TimeUnit.SECONDS.toMillis(2));
            }
            runOnEdt(() -> {
            });
        }
    }

    @Test
    @DisplayName("Cancelling a managed login restores the authorization controls")
    void configureCopilotAuthAction_whenLoginIsCancelled_restoresControls() throws Exception {
        var loginStarted = new CountDownLatch(1);
        var releaseLogin = new CountDownLatch(1);
        var loginCompleted = new CountDownLatch(1);
        CopilotAuthResolver copilotAuthResolver = new CopilotAuthResolver(
                tempDir.resolve("cancelled-ui-copilot-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        ) {
            @Override
            public CopilotAuthStatus resolveStatus() {
                return CopilotAuthStatus.unauthorized("Not authorized");
            }

            @Override
            public boolean isOAuthClientConfigured() {
                return true;
            }

            @Override
            public CopilotAuthActionResult login(
                    BooleanSupplier cancellationRequested,
                    Predicate<Runnable> promptActionGate,
                    Consumer<CopilotLoginChallenge> challengeConsumer
            ) {
                loginStarted.countDown();
                try {
                    releaseLogin.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                Thread.currentThread().interrupt();
                loginCompleted.countDown();
                return CopilotAuthActionResult.failure("GitHub Copilot login cancelled.");
            }
        };
        ProvidersPanel subject = callOnEdt(() -> new ProvidersPanel(
                new SettingsRepository(tempDir.resolve("cancelled-ui.properties")),
                new ApiTokenFieldRegistry(),
                SettingsCredentialChangeListener.NO_OP,
                copilotAuthResolver,
                new CodexAuthResolver(
                        tempDir.resolve("cancelled-ui-codex-home"),
                        emptyMap(),
                        HttpClient.newHttpClient()
                )
        ));

        try {
            runOnEdt(subject::addNotify);
            runOnEdt(() -> {
                JList<?> providerList = findList(subject);
                assertThat(providerList).isNotNull();
                providerList.setSelectedValue("GitHub Copilot", true);
            });
            awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> findVisibleButton(subject, "Login") != null));
            JButton loginButton = callOnEdt(() -> findVisibleButton(subject, "Login"));
            assertThat(loginButton).isNotNull();
            runOnEdt(loginButton::doClick);
            assertThat(loginStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(callOnEdt(() -> findVisibleButton(subject, "Working...") != null)).isTrue();

            releaseLogin.countDown();
            assertThat(loginCompleted.await(2, TimeUnit.SECONDS)).isTrue();
            awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> {
                JButton restoredButton = findVisibleButton(subject, "Login");
                return restoredButton != null && restoredButton.isEnabled();
            }));

            assertThat(callOnEdt(() -> containsLabelText(subject, "GitHub Copilot login cancelled."))).isTrue();
        } finally {
            releaseLogin.countDown();
            runOnEdt(subject::removeNotify);
            runOnEdt(() -> {
            });
        }
    }

    @Test
    @DisplayName("Application invalidation failures still restore logged-out managed auth controls")
    void configureAuthAction_whenLogoutListenerThrows_restoresCopilotAndCodexControls() throws Exception {
        var copilotAuthorized = new AtomicBoolean(true);
        var codexAuthorized = new AtomicBoolean(true);
        var copilotWorker = new AtomicReference<Thread>();
        var codexWorker = new AtomicReference<Thread>();
        CopilotAuthResolver copilotAuthResolver = new CopilotAuthResolver(
                tempDir.resolve("throwing-listener-copilot-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        ) {
            @Override
            public CopilotAuthStatus resolveStatus() {
                return copilotAuthorized.get()
                        ? CopilotAuthStatus.authorized("Authorized", "Chat4J OAuth")
                        : CopilotAuthStatus.unauthorized("Not authorized");
            }

            @Override
            public boolean isOAuthClientConfigured() {
                return true;
            }

            @Override
            public CopilotAuthActionResult logout(BooleanSupplier cancellationRequested) {
                copilotWorker.set(Thread.currentThread());
                copilotAuthorized.set(false);
                return CopilotAuthActionResult.success("Logged out.");
            }
        };
        CodexAuthResolver codexAuthResolver = new CodexAuthResolver(
                tempDir.resolve("throwing-listener-codex-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        ) {
            @Override
            public CodexAuthStatus resolveStatus() {
                return codexAuthorized.get()
                        ? CodexAuthStatus.authorized("Authorized", "Chat4J OAuth")
                        : CodexAuthStatus.unauthorized("Not authorized");
            }

            @Override
            public boolean isOAuthClientConfigured() {
                return true;
            }

            @Override
            public CodexAuthActionResult logout(BooleanSupplier cancellationRequested) {
                codexWorker.set(Thread.currentThread());
                codexAuthorized.set(false);
                return CodexAuthActionResult.success("Logged out.");
            }
        };
        SettingsCredentialChangeListener listener = throwingAuthChangeListener();
        ProvidersPanel subject = callOnEdt(() -> new ProvidersPanel(
                new SettingsRepository(tempDir.resolve("throwing-logout-listener.properties")),
                new ApiTokenFieldRegistry(),
                listener,
                copilotAuthResolver,
                codexAuthResolver
        ));

        try {
            runOnEdt(subject::addNotify);
            runOnEdt(() -> selectProvider(subject, "GitHub Copilot"));
            awaitEnabledVisibleButton(subject, "Log out");
            runOnEdt(() -> findVisibleButton(subject, "Log out").doClick());
            awaitEnabledVisibleButton(subject, "Login");
            assertThat(callOnEdt(() -> containsVisibleLabelText(subject, "Not authorized"))).isTrue();

            runOnEdt(() -> selectProvider(subject, "OpenAI Codex"));
            awaitEnabledVisibleButton(subject, "Log out");
            runOnEdt(() -> findVisibleButton(subject, "Log out").doClick());
            awaitEnabledVisibleButton(subject, "Login");
            assertThat(callOnEdt(() -> containsVisibleLabelText(subject, "Not authorized"))).isTrue();
        } finally {
            runOnEdt(subject::removeNotify);
            joinWorker(copilotWorker.get());
            joinWorker(codexWorker.get());
            runOnEdt(() -> {
            });
        }
    }

    @Test
    @DisplayName("Application invalidation failures still restore logged-in managed auth controls")
    void configureAuthAction_whenLoginListenerThrows_restoresCopilotAndCodexControls() throws Exception {
        var copilotAuthorized = new AtomicBoolean();
        var codexAuthorized = new AtomicBoolean();
        var copilotWorker = new AtomicReference<Thread>();
        var codexWorker = new AtomicReference<Thread>();
        CopilotAuthResolver copilotAuthResolver = new CopilotAuthResolver(
                tempDir.resolve("throwing-login-listener-copilot-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        ) {
            @Override
            public CopilotAuthStatus resolveStatus() {
                return copilotAuthorized.get()
                        ? CopilotAuthStatus.authorized("Authorized", "Chat4J OAuth")
                        : CopilotAuthStatus.unauthorized("Not authorized");
            }

            @Override
            public boolean isOAuthClientConfigured() {
                return true;
            }

            @Override
            public CopilotAuthActionResult login(
                    BooleanSupplier cancellationRequested,
                    Predicate<Runnable> promptActionGate,
                    Consumer<CopilotLoginChallenge> challengeConsumer
            ) {
                copilotWorker.set(Thread.currentThread());
                copilotAuthorized.set(true);
                return CopilotAuthActionResult.success("Logged in.");
            }
        };
        CodexAuthResolver codexAuthResolver = new CodexAuthResolver(
                tempDir.resolve("throwing-login-listener-codex-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        ) {
            @Override
            public CodexAuthStatus resolveStatus() {
                return codexAuthorized.get()
                        ? CodexAuthStatus.authorized("Authorized", "Chat4J OAuth")
                        : CodexAuthStatus.unauthorized("Not authorized");
            }

            @Override
            public boolean isOAuthClientConfigured() {
                return true;
            }

            @Override
            public CodexAuthActionResult login(
                    BooleanSupplier cancellationRequested,
                    AuthorizationInputProvider authorizationInputProvider
            ) {
                codexWorker.set(Thread.currentThread());
                codexAuthorized.set(true);
                return CodexAuthActionResult.success("Logged in.");
            }
        };
        SettingsCredentialChangeListener listener = throwingAuthChangeListener();
        ProvidersPanel subject = callOnEdt(() -> new ProvidersPanel(
                new SettingsRepository(tempDir.resolve("throwing-login-listener.properties")),
                new ApiTokenFieldRegistry(),
                listener,
                copilotAuthResolver,
                codexAuthResolver
        ));

        try {
            runOnEdt(subject::addNotify);
            runOnEdt(() -> selectProvider(subject, "GitHub Copilot"));
            awaitEnabledVisibleButton(subject, "Login");
            runOnEdt(() -> findVisibleButton(subject, "Login").doClick());
            awaitEnabledVisibleButton(subject, "Log out");
            assertThat(callOnEdt(() -> containsVisibleLabelText(subject, "Authorized"))).isTrue();

            runOnEdt(() -> selectProvider(subject, "OpenAI Codex"));
            awaitEnabledVisibleButton(subject, "Login");
            runOnEdt(() -> findVisibleButton(subject, "Login").doClick());
            awaitEnabledVisibleButton(subject, "Log out");
            assertThat(callOnEdt(() -> containsVisibleLabelText(subject, "Authorized"))).isTrue();
        } finally {
            runOnEdt(subject::removeNotify);
            joinWorker(copilotWorker.get());
            joinWorker(codexWorker.get());
            runOnEdt(() -> {
            });
        }
    }

    @Test
    @DisplayName("Reattached panels refresh status after an older lifecycle mutation commits")
    void configureCopilotAuthAction_whenPanelReattachedBeforeMutationCompletes_refreshesCurrentControls() throws Exception {
        var logoutStarted = new CountDownLatch(1);
        var releaseLogout = new CountDownLatch(1);
        var loggedOut = new AtomicBoolean();
        var authWorker = new AtomicReference<Thread>();
        CopilotAuthResolver copilotAuthResolver = new CopilotAuthResolver(
                tempDir.resolve("reattached-copilot-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        ) {
            @Override
            public CopilotAuthStatus resolveStatus() {
                return loggedOut.get()
                        ? CopilotAuthStatus.unauthorized("Not authorized")
                        : CopilotAuthStatus.authorized("Authorized", "Chat4J OAuth");
            }

            @Override
            public boolean isOAuthClientConfigured() {
                return true;
            }

            @Override
            public CopilotAuthActionResult logout(BooleanSupplier cancellationRequested) {
                authWorker.set(Thread.currentThread());
                logoutStarted.countDown();
                boolean interrupted = false;
                while (releaseLogout.getCount() > 0) {
                    try {
                        releaseLogout.await();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                loggedOut.set(true);
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return CopilotAuthActionResult.success("Logged out.");
            }
        };
        ProvidersPanel subject = callOnEdt(() -> new ProvidersPanel(
                new SettingsRepository(tempDir.resolve("reattached.properties")),
                new ApiTokenFieldRegistry(),
                SettingsCredentialChangeListener.NO_OP,
                copilotAuthResolver,
                new CodexAuthResolver(
                        tempDir.resolve("reattached-codex-home"),
                        emptyMap(),
                        HttpClient.newHttpClient()
                )
        ));

        try {
            runOnEdt(subject::addNotify);
            runOnEdt(() -> selectProvider(subject, "GitHub Copilot"));
            awaitEnabledVisibleButton(subject, "Log out");
            runOnEdt(() -> findVisibleButton(subject, "Log out").doClick());
            assertThat(logoutStarted.await(2, TimeUnit.SECONDS)).isTrue();

            runOnEdt(subject::removeNotify);
            runOnEdt(subject::addNotify);
            runOnEdt(() -> selectProvider(subject, "GitHub Copilot"));
            awaitEnabledVisibleButton(subject, "Log out");

            releaseLogout.countDown();

            awaitEnabledVisibleButton(subject, "Login");
        } finally {
            releaseLogout.countDown();
            runOnEdt(subject::removeNotify);
            joinWorker(authWorker.get());
            runOnEdt(() -> {
            });
        }
    }

    @Test
    @DisplayName("Managed authentication status is resolved off the event dispatch thread")
    void addNotify_whenManagedAuthStatusIsResolved_keepsResolutionOffEdt() throws Exception {
        var statusResolved = new CountDownLatch(2);
        var copilotResolvedOnEdt = new AtomicReference<Boolean>();
        var codexResolvedOnEdt = new AtomicReference<Boolean>();
        CopilotAuthResolver copilotAuthResolver = new CopilotAuthResolver(
                tempDir.resolve("status-thread-copilot-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        ) {
            @Override
            public CopilotAuthStatus resolveStatus() {
                copilotResolvedOnEdt.set(SwingUtilities.isEventDispatchThread());
                statusResolved.countDown();
                return CopilotAuthStatus.unauthorized("Not authorized");
            }
        };
        CodexAuthResolver codexAuthResolver = new CodexAuthResolver(
                tempDir.resolve("status-thread-codex-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        ) {
            @Override
            public CodexAuthStatus resolveStatus() {
                codexResolvedOnEdt.set(SwingUtilities.isEventDispatchThread());
                statusResolved.countDown();
                return CodexAuthStatus.unauthorized("Not authorized");
            }
        };
        ProvidersPanel subject = callOnEdt(() -> new ProvidersPanel(
                new SettingsRepository(tempDir.resolve("status-thread.properties")),
                new ApiTokenFieldRegistry(),
                SettingsCredentialChangeListener.NO_OP,
                copilotAuthResolver,
                codexAuthResolver
        ));

        try {
            assertThat(statusResolved.getCount()).isEqualTo(2L);
            runOnEdt(subject::addNotify);
            assertThat(statusResolved.await(2, TimeUnit.SECONDS)).isTrue();
            runOnEdt(() -> {
            });

            assertThat(copilotResolvedOnEdt).hasValue(false);
            assertThat(codexResolvedOnEdt).hasValue(false);
        } finally {
            runOnEdt(subject::removeNotify);
            runOnEdt(() -> {
            });
        }
    }

    @Test
    @DisplayName("Closing a managed login flow cancels polling and releases the auth operation guard")
    void authFlowCancellation_whenCopilotPollingIsActive_releasesGuardForNextOperation() throws Exception {
        var pollingStarted = new CountDownLatch(1);
        var releasePolling = new CountDownLatch(1);
        var requestCount = new AtomicInteger();
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> deviceResponse = mock(HttpResponse.class);
        when(deviceResponse.statusCode()).thenReturn(200);
        when(deviceResponse.body()).thenReturn("""
                {
                  "device_code": "device-code-123",
                  "user_code": "ABCD-1234",
                  "verification_uri": "https://github.com/login/device",
                  "expires_in": 600,
                  "interval": 2
                }
                """);
        when(httpClient.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        )).thenAnswer(invocation -> {
            if (requestCount.incrementAndGet() == 1) {
                return deviceResponse;
            }
            pollingStarted.countDown();
            releasePolling.await();
            throw new AssertionError("Cancelled polling should not continue");
        });
        var resolver = new CopilotAuthResolver(
                tempDir.resolve("dialog-cancellation-home"),
                Map.of("CHAT4J_COPILOT_OAUTH_CLIENT_ID", "chat4j-client-id"),
                httpClient
        );
        var dialogCancellationRequested = new AtomicBoolean();
        var loginResult = new AtomicReference<CopilotAuthResolver.CopilotAuthActionResult>();
        Thread loginWorker = Thread.startVirtualThread(() -> loginResult.set(resolver.login(
                dialogCancellationRequested::get,
                ignored -> true,
                ignored -> {
                }
        )));

        try {
            assertThat(pollingStarted.await(2, TimeUnit.SECONDS)).isTrue();
            ProvidersPanel.authFlowCancellation(loginWorker, dialogCancellationRequested).run();
            loginWorker.join(TimeUnit.SECONDS.toMillis(2));

            assertThat(loginWorker.isAlive()).isFalse();
            assertThat(dialogCancellationRequested).isTrue();
            assertThat(loginResult.get().success()).isFalse();
            assertThat(loginResult.get().message()).contains("cancelled");
            assertThat(resolver.logout().success()).isTrue();
        } finally {
            dialogCancellationRequested.set(true);
            releasePolling.countDown();
            loginWorker.interrupt();
            loginWorker.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    @Test
    @DisplayName("Codex dialog timeout closes the callback listener and dismisses the dialog")
    void createAuthDialogTimeout_whenDeadlineExpires_closesCallbackAndDismissesDialog() throws Exception {
        var callbackClosed = new CountDownLatch(1);
        var dialogDismissed = new CountDownLatch(1);
        var closeCalls = new AtomicInteger();
        var timedOut = new AtomicBoolean();
        var callbackWait = new CodexAuthResolver.CodexCallbackWait(
                true,
                "Listening",
                new java.util.concurrent.CompletableFuture<>(),
                () -> {
                    closeCalls.incrementAndGet();
                    callbackClosed.countDown();
                }
        );
        Timer timeoutTimer = callOnEdt(() -> ProvidersPanel.createAuthDialogTimeout(
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25),
                timedOut,
                callbackWait,
                dialogDismissed::countDown
        ));

        try {
            runOnEdt(timeoutTimer::start);
            assertThat(callbackClosed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(dialogDismissed.await(2, TimeUnit.SECONDS)).isTrue();
            runOnEdt(() -> {
            });
            callbackWait.close();

            assertThat(timedOut).isTrue();
            assertThat(closeCalls).hasValue(1);
        } finally {
            runOnEdt(timeoutTimer::stop);
        }
    }

    @Test
    @DisplayName("Browser failure diagnostics omit authorization queries and exception messages")
    void sanitizedBrowserFailureDescription_whenUrlAndExceptionContainSecrets_masksSensitiveValues() {
        String authorizationUrl = "https://secret-user:secret-password@auth.example.com/oauth?state=secret-state&code_challenge=secret-challenge";
        var failure = new IllegalStateException("failed for %s".formatted(authorizationUrl));

        String sanitizedUrl = ProvidersPanel.sanitizedUrlForDiagnostics(authorizationUrl);
        String sanitizedFailure = ProvidersPanel.sanitizedBrowserFailureDescription(failure);

        assertThat(sanitizedUrl)
                .isEqualTo("https://auth.example.com/oauth")
                .doesNotContain("secret-user", "secret-password", "secret-state", "secret-challenge");
        assertThat(sanitizedFailure)
                .isEqualTo("IllegalStateException")
                .doesNotContain("secret-state", "secret-challenge");
    }

    @Test
    @DisplayName("Provider panel base URL helper falls back for blanks and trims configured values")
    void fallbackBlankProviderBaseUrl_whenValueIsConfigured_returnsNormalizedValue() {
        assertThat(ProvidersPanel.fallbackBlankProviderBaseUrl(null, "http://localhost:1234/v1"))
                .isEqualTo("http://localhost:1234/v1");
        assertThat(ProvidersPanel.fallbackBlankProviderBaseUrl("   ", "http://localhost:1234/v1"))
                .isEqualTo("http://localhost:1234/v1");
        assertThat(ProvidersPanel.fallbackBlankProviderBaseUrl(
                "  http://127.0.0.1:1234/v1  ",
                "http://localhost:1234/v1"
        )).isEqualTo("http://127.0.0.1:1234/v1");
    }

    private static SettingsCredentialChangeListener throwingAuthChangeListener() {
        return new SettingsCredentialChangeListener() {
            @Override
            public void credentialChanged(String canonicalTokenId) {
            }

            @Override
            public void providerAuthChanged(String providerName) {
                throw new IllegalStateException("invalidation failed");
            }
        };
    }

    private ProvidersPanel newPanel(SettingsRepository settingsRepo) {
        return new ProvidersPanel(
                settingsRepo,
                new ApiTokenFieldRegistry(),
                SettingsCredentialChangeListener.NO_OP,
                new CopilotAuthResolver(
                        tempDir.resolve("copilot-home"),
                        emptyMap(),
                        HttpClient.newHttpClient()
                ),
                new CodexAuthResolver(
                        tempDir.resolve("codex-home"),
                        emptyMap(),
                        HttpClient.newHttpClient()
                )
        );
    }

    private static JButton findButton(Component component, String text) {
        if (component instanceof JButton button && text.equals(button.getText())) {
            return button;
        }
        if (component instanceof Container container) {
            return stream(container.getComponents())
                    .map(child -> findButton(child, text))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private static void selectProvider(ProvidersPanel panel, String providerName) {
        JList<?> providerList = findList(panel);
        assertThat(providerList).isNotNull();
        providerList.setSelectedValue(providerName, true);
    }

    private static void awaitEnabledVisibleButton(ProvidersPanel panel, String text) throws Exception {
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> {
            JButton button = findVisibleButton(panel, text);
            return button != null && button.isEnabled();
        }));
    }

    private static void joinWorker(Thread worker) throws InterruptedException {
        if (worker != null) {
            worker.interrupt();
            worker.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    private static JButton findVisibleButton(Component component, String text) {
        if (!component.isVisible()) {
            return null;
        }
        if (component instanceof JButton button && text.equals(button.getText())) {
            return button;
        }
        if (component instanceof Container container) {
            return stream(container.getComponents())
                    .map(child -> findVisibleButton(child, text))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private static JList<?> findList(Component component) {
        if (component instanceof JList<?> list) {
            return list;
        }
        if (component instanceof Container container) {
            return stream(container.getComponents())
                    .map(ProvidersPanelTest::findList)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private static boolean containsLabelText(Component component, String expectedText) {
        if (component instanceof JLabel label && expectedText.equals(label.getText())) {
            return true;
        }
        if (!(component instanceof Container container)) {
            return false;
        }
        return stream(container.getComponents())
                .anyMatch(child -> containsLabelText(child, expectedText));
    }

    private static boolean containsVisibleLabelText(Component component, String expectedText) {
        if (!component.isVisible()) {
            return false;
        }
        if (component instanceof JLabel label && expectedText.equals(label.getText())) {
            return true;
        }
        if (!(component instanceof Container container)) {
            return false;
        }
        return stream(container.getComponents())
                .anyMatch(child -> containsVisibleLabelText(child, expectedText));
    }

    private static void awaitCondition(long timeout, TimeUnit unit, CheckedBooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void runOnEdt(ThrowingAction action) throws Exception {
        callOnEdt(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T callOnEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }

        var result = new AtomicReference<T>();
        var error = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() instanceof Exception e) {
            throw e;
        }
        if (error.get() instanceof Error e) {
            throw e;
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return result.get();
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private ThrowingSettingsRepo() {
            super(Path.of("unused-providers-panel.properties"));
        }

        @Override
        public String get(String key, String defaultValue) {
            throw new IllegalStateException("forced failure");
        }
    }
}
