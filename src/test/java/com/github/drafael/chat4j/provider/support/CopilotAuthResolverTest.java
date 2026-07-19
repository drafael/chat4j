package com.github.drafael.chat4j.provider.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CopilotAuthResolverTest {

    private static final String DEVICE_CODE_ENDPOINT_PROPERTY = "chat4j.copilot.deviceCodeEndpoint";
    private static final String ACCESS_TOKEN_ENDPOINT_PROPERTY = "chat4j.copilot.accessTokenEndpoint";
    private static final String OAUTH_CLIENT_ID_PROPERTY = "chat4j.copilot.oauthClientId";
    private static final String OAUTH_SCOPES_PROPERTY = "chat4j.copilot.oauthScopes";
    private static final String COPILOT_TOKEN_ENDPOINT_PROPERTY = "chat4j.copilot.tokenEndpoint";
    private static final String COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY = "chat4j.copilot.allowCustomTokenEndpoint";
    private static final String DUMMY_GITHUB_ACCESS_TOKEN = "DUMMY_GITHUB_ACCESS_TOKEN_FOR_TESTS";
    private static final String DUMMY_COPILOT_SESSION_TOKEN = "tid=dummy-copilot-session-token-for-tests;exp=4070908800";
    private static final Set<String> MANAGED_SYSTEM_PROPERTIES = Set.of(
            DEVICE_CODE_ENDPOINT_PROPERTY,
            ACCESS_TOKEN_ENDPOINT_PROPERTY,
            OAUTH_CLIENT_ID_PROPERTY,
            OAUTH_SCOPES_PROPERTY,
            COPILOT_TOKEN_ENDPOINT_PROPERTY,
            COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY
    );

    @TempDir
    Path tempDir;

    private final Map<String, String> originalSystemProperties = new HashMap<>();

    @BeforeEach
    void setUp() {
        MANAGED_SYSTEM_PROPERTIES.forEach(property -> originalSystemProperties.put(property, System.getProperty(property)));
        MANAGED_SYSTEM_PROPERTIES.forEach(System::clearProperty);
    }

    @AfterEach
    void tearDown() {
        originalSystemProperties.forEach((property, value) -> {
            if (value == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, value);
            }
        });
    }

    @Test
    @DisplayName("OAuth client detection supports system property configuration")
    void isOAuthClientConfigured_whenSystemPropertyPresent_returnsTrue() {
        Path userHome = tempDir.resolve("home");
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-client-id");

        var subject = new CopilotAuthResolver(userHome, emptyMap(), HttpClient.newHttpClient());

        assertThat(subject.isOAuthClientConfigured()).isTrue();
    }

    @Test
    @DisplayName("Resolver reports unauthorized when no stored Chat4J token exists")
    void resolveStatus_whenNoStoredToken_returnsUnauthorized() {
        Path userHome = tempDir.resolve("home");
        var subject = new CopilotAuthResolver(userHome, emptyMap(), HttpClient.newHttpClient());

        var status = subject.resolveStatus();

        assertThat(status.authorized()).isFalse();
        assertThat(status.message()).contains("Not authorized");
    }

    @Test
    @DisplayName("Copilot session exchange rejects tokens whose provider expiry is already past")
    void resolveBearerTokenOrNull_whenExchangeReturnsExpiredSessionToken_returnsNull() throws Exception {
        Path userHome = tempDir.resolve("expired-exchange-home");
        Path tokenFile = userHome.resolve(".config/chat4j/copilot-auth.json");
        Files.createDirectories(tokenFile.getParent());
        Files.writeString(tokenFile, """
                {
                  "accessToken": "tid=expired-copilot-session-token;exp=1",
                  "refreshToken": "DUMMY_GITHUB_ACCESS_TOKEN_FOR_TESTS",
                  "expiresAtEpochMs": 1,
                  "oauthScopes": "read:user"
                }
                """);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"token":"tid=already-expired-session-token;exp=1","expires_at":1}
                """);
        when(httpClient.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        )).thenReturn(response);
        var subject = new CopilotAuthResolver(userHome, emptyMap(), httpClient);

        String resolved = subject.resolveBearerTokenOrNull();

        assertThat(resolved).isNull();
        assertThat(tokenFile).content().doesNotContain("tid=already-expired-session-token");
    }

    @Test
    @DisplayName("Login flow stores Chat4J OAuth token, requests minimum OAuth scopes, opens browser, and copies code")
    void login_whenDeviceFlowCompletes_storesTokenAndTriggersPromptActions() throws Exception {
        Path userHome = tempDir.resolve("home");
        AtomicReference<String> deviceRequestBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/device", exchange -> {
            deviceRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {
                      "device_code": "device-code-123",
                      "user_code": "ABCD-1234",
                      "verification_uri": "https://github.com/login/device",
                      "expires_in": 600,
                      "interval": 1
                    }
                    """.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/token", exchange -> {
            byte[] body = """
                    {"access_token":"DUMMY_GITHUB_ACCESS_TOKEN_FOR_TESTS"}
                    """.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/session", exchange -> {
            byte[] body = """
                    {"token":"tid=dummy-copilot-session-token-for-tests;exp=4070908800","expires_at":4070908800}
                    """.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            System.setProperty(DEVICE_CODE_ENDPOINT_PROPERTY, "http://127.0.0.1:%d/device".formatted(port));
            System.setProperty(ACCESS_TOKEN_ENDPOINT_PROPERTY, "http://127.0.0.1:%d/token".formatted(port));
            System.setProperty(COPILOT_TOKEN_ENDPOINT_PROPERTY, "http://127.0.0.1:%d/session".formatted(port));
            System.setProperty(COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY, "true");

            CopilotAuthResolver.UserPromptActions promptActions = mock(CopilotAuthResolver.UserPromptActions.class);
            var subject = new CopilotAuthResolver(
                    userHome,
                    Map.of("CHAT4J_COPILOT_OAUTH_CLIENT_ID", "chat4j-client-id"),
                    HttpClient.newHttpClient(),
                    promptActions
            );

            CopilotAuthResolver.CopilotAuthActionResult result = subject.login();

            assertThat(result.success()).isTrue();
            assertThat(subject.resolveBearerTokenOrNull())
                    .isEqualTo(DUMMY_COPILOT_SESSION_TOKEN);
            assertThat(subject.resolveStatus().authorized()).isTrue();
            assertThat(subject.resolveStatus().source()).isEqualTo("Chat4J OAuth");
            assertThat(deviceRequestBody.get())
                    .contains("client_id=chat4j-client-id")
                    .contains("scope=read%3Auser");

            Path tokenFile = userHome.resolve(".config/chat4j/copilot-auth.json");
            if (tokenFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                assertThat(Files.getPosixFilePermissions(tokenFile))
                        .isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            }

            verify(promptActions).copyCodeToClipboard("ABCD-1234");
            verify(promptActions).openBrowser("https://github.com/login/device");
            verifyNoMoreInteractions(promptActions);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Unapproved custom token endpoints receive no request during stored token migration")
    void resolveBearerTokenOrNull_whenCustomEndpointIsNotApproved_usesTrustedGitHubEndpoint() throws Exception {
        var maliciousRequests = new AtomicInteger();
        var maliciousAuthorizationHeaders = new AtomicReference<String>();
        HttpServer maliciousServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        maliciousServer.createContext("/session", exchange -> {
            maliciousRequests.incrementAndGet();
            maliciousAuthorizationHeaders.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        maliciousServer.start();

        try {
            System.setProperty(
                    COPILOT_TOKEN_ENDPOINT_PROPERTY,
                    "http://127.0.0.1:%d/session".formatted(maliciousServer.getAddress().getPort())
            );
            System.clearProperty(COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY);

            String freshToken = "gho_unapproved_resolver_%s".formatted(UUID.randomUUID());
            Path userHome = tempDir.resolve("unapproved-endpoint-home");
            Path tokenFile = userHome.resolve(".config/chat4j/copilot-auth.json");
            Files.createDirectories(tokenFile.getParent());
            Files.writeString(tokenFile, """
                    {
                      "accessToken": "%s",
                      "refreshToken": "%s",
                      "oauthScopes": "read:user"
                    }
                    """.formatted(freshToken, freshToken));

            var requestedUri = new AtomicReference<URI>();
            var requestedAuthorizationHeader = new AtomicReference<String>();
            HttpClient httpClient = mock(HttpClient.class);
            HttpResponse<String> unauthorizedResponse = mock(HttpResponse.class);
            when(unauthorizedResponse.statusCode()).thenReturn(401);
            when(httpClient.send(
                    any(HttpRequest.class),
                    org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
            )).thenAnswer(invocation -> {
                HttpRequest request = invocation.getArgument(0);
                requestedUri.set(request.uri());
                requestedAuthorizationHeader.set(request.headers().firstValue("Authorization").orElse(null));
                return unauthorizedResponse;
            });
            var subject = new CopilotAuthResolver(userHome, emptyMap(), httpClient);

            assertThat(subject.resolveBearerTokenOrNull()).isNull();
            assertThat(requestedUri).hasValue(
                    URI.create("https://api.github.com/copilot_internal/v2/token")
            );
            assertThat(requestedAuthorizationHeader).hasValue("Bearer %s".formatted(freshToken));
            assertThat(maliciousRequests).hasValue(0);
            assertThat(maliciousAuthorizationHeaders).hasNullValue();
        } finally {
            maliciousServer.stop(0);
        }
    }

    @Test
    @DisplayName("Cancelling after the device response prevents clipboard and browser prompts")
    void beginLogin_whenCancelledAfterDeviceResponse_skipsPromptActions() throws Exception {
        var postResponseCheckStarted = new CountDownLatch(1);
        var releasePostResponseCheck = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var cancellationChecks = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/device", exchange -> {
            byte[] body = """
                    {
                      "device_code": "device-code-123",
                      "user_code": "ABCD-1234",
                      "verification_uri": "https://github.com/login/device",
                      "expires_in": 600,
                      "interval": 2
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        var failure = new AtomicReference<Throwable>();
        Thread worker = null;
        try {
            System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-client-id");
            System.setProperty(
                    DEVICE_CODE_ENDPOINT_PROPERTY,
                    "http://127.0.0.1:%d/device".formatted(server.getAddress().getPort())
            );
            CopilotAuthResolver.UserPromptActions promptActions = mock(CopilotAuthResolver.UserPromptActions.class);
            var subject = new CopilotAuthResolver(
                    tempDir.resolve("home"),
                    emptyMap(),
                    HttpClient.newHttpClient(),
                    promptActions
            );

            worker = Thread.startVirtualThread(() -> {
                try {
                    subject.beginLogin(() -> {
                        if (cancellationChecks.incrementAndGet() > 1) {
                            postResponseCheckStarted.countDown();
                            try {
                                releasePostResponseCheck.await(2, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        return cancelled.get();
                    });
                } catch (Throwable t) {
                    failure.set(t);
                }
            });

            assertThat(postResponseCheckStarted.await(2, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
            releasePostResponseCheck.countDown();
            worker.join(TimeUnit.SECONDS.toMillis(2));

            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cancelled");
            verifyNoInteractions(promptActions);
        } finally {
            releasePostResponseCheck.countDown();
            if (worker != null) {
                worker.interrupt();
                worker.join(TimeUnit.SECONDS.toMillis(2));
            }
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Deactivating the prompt action gate after a cancellation check prevents prompt side effects")
    void beginLogin_whenLifecycleDeactivatesBeforePromptAction_skipsClipboardAndBrowser() throws Exception {
        var gateEntered = new CountDownLatch(1);
        var releaseGate = new CountDownLatch(1);
        var lifecycleActive = new AtomicBoolean(true);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
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
        )).thenReturn(response);
        CopilotAuthResolver.UserPromptActions promptActions = mock(CopilotAuthResolver.UserPromptActions.class);
        var subject = new CopilotAuthResolver(
                tempDir.resolve("home"),
                emptyMap(),
                httpClient,
                promptActions
        );
        var failure = new AtomicReference<Throwable>();
        Thread worker = null;

        try {
            System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-client-id");
            worker = Thread.startVirtualThread(() -> {
                try {
                    subject.beginLogin(
                            () -> !lifecycleActive.get(),
                            action -> {
                                gateEntered.countDown();
                                try {
                                    releaseGate.await(2, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return false;
                                }
                                if (!lifecycleActive.get()) {
                                    return false;
                                }
                                action.run();
                                return true;
                            }
                    );
                } catch (Throwable t) {
                    failure.set(t);
                }
            });

            assertThat(gateEntered.await(2, TimeUnit.SECONDS)).isTrue();
            lifecycleActive.set(false);
            releaseGate.countDown();
            worker.join(TimeUnit.SECONDS.toMillis(2));

            assertThat(worker.isAlive()).isFalse();
            assertThat(failure).hasNullValue();
            verifyNoInteractions(promptActions);
        } finally {
            lifecycleActive.set(false);
            releaseGate.countDown();
            if (worker != null) {
                worker.interrupt();
                worker.join(TimeUnit.SECONDS.toMillis(2));
            }
        }
    }

    @Test
    @DisplayName("Cancelling a blocked device login prevents credential persistence")
    void completeLogin_whenPollingIsCancelled_stopsWorkerWithoutWritingToken() throws Exception {
        Path userHome = tempDir.resolve("home");
        var requestStarted = new CountDownLatch(1);
        var releaseRequest = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            requestStarted.countDown();
            try {
                releaseRequest.await(5, TimeUnit.SECONDS);
                byte[] body = "{\"access_token\":\"DUMMY_GITHUB_ACCESS_TOKEN_FOR_TESTS\"}".getBytes();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        var cancelled = new AtomicBoolean();
        var result = new AtomicReference<CopilotAuthResolver.CopilotAuthActionResult>();
        Thread worker = null;
        try {
            System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-client-id");
            System.setProperty(
                    ACCESS_TOKEN_ENDPOINT_PROPERTY,
                    "http://127.0.0.1:%d/token".formatted(server.getAddress().getPort())
            );
            var subject = new CopilotAuthResolver(userHome, emptyMap(), HttpClient.newHttpClient());
            var challenge = new CopilotAuthResolver.CopilotLoginChallenge(
                    "device-code",
                    "user-code",
                    "https://github.com/login/device",
                    2,
                    60,
                    Long.MAX_VALUE,
                    "read:user",
                    null
            );
            worker = Thread.startVirtualThread(() -> result.set(subject.completeLogin(challenge, cancelled::get)));

            assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
            worker.interrupt();
            worker.join(TimeUnit.SECONDS.toMillis(2));

            assertThat(worker.isAlive()).isFalse();
            assertThat(worker.isInterrupted()).isTrue();
            assertThat(result.get().success()).isFalse();
            assertThat(userHome.resolve(".config/chat4j/copilot-auth.json")).doesNotExist();
        } finally {
            cancelled.set(true);
            releaseRequest.countDown();
            if (worker != null) {
                worker.interrupt();
                worker.join(TimeUnit.SECONDS.toMillis(2));
            }
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Cancellation while final publication is waiting does not replace stored credentials")
    void completeLogin_whenCancelledWhileWaitingForAuthFileLock_doesNotPublishLoginToken() throws Exception {
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-client-id");
        Path userHome = tempDir.resolve("cancelled-publication-home");
        Path tokenFile = userHome.resolve(".config/chat4j/copilot-auth.json");
        Files.createDirectories(tokenFile.getParent());
        Files.writeString(tokenFile, """
                {
                  "accessToken": "tid=expired-copilot-session-token;exp=1",
                  "refreshToken": "gho_refresh_token_for_publication_test",
                  "expiresAtEpochMs": 1,
                  "oauthScopes": "read:user"
                }
                """);
        var refreshStarted = new CountDownLatch(1);
        var releaseRefresh = new CountDownLatch(1);
        var loginExchangeCompleted = new AtomicBoolean();
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> refreshedResponse = mock(HttpResponse.class);
        when(refreshedResponse.statusCode()).thenReturn(200);
        when(refreshedResponse.body()).thenReturn(
                "{\"token\":\"tid=refreshed-copilot-session-token;exp=4070908800\",\"expires_at\":4070908800}"
        );
        HttpResponse<String> accessTokenResponse = mock(HttpResponse.class);
        when(accessTokenResponse.statusCode()).thenReturn(200);
        when(accessTokenResponse.body()).thenReturn("{\"access_token\":\"DUMMY_GITHUB_ACCESS_TOKEN_FOR_TESTS\"}");
        HttpResponse<String> loginSessionResponse = mock(HttpResponse.class);
        when(loginSessionResponse.statusCode()).thenReturn(200);
        when(loginSessionResponse.body()).thenReturn(
                "{\"token\":\"tid=login-copilot-session-token;exp=4070908800\",\"expires_at\":4070908800}"
        );
        when(httpClient.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        )).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            String authorization = request.headers().firstValue("Authorization").orElse("");
            if (authorization.contains("gho_refresh_token_for_publication_test")) {
                refreshStarted.countDown();
                releaseRefresh.await(2, TimeUnit.SECONDS);
                return refreshedResponse;
            }
            if ("POST".equals(request.method())) {
                return accessTokenResponse;
            }
            loginExchangeCompleted.set(true);
            return loginSessionResponse;
        });
        var subject = new CopilotAuthResolver(userHome, emptyMap(), httpClient);
        var cancelled = new AtomicBoolean();
        var finalCancellationCheck = new CountDownLatch(1);
        var checksAfterExchange = new AtomicInteger();
        var result = new AtomicReference<CopilotAuthResolver.CopilotAuthActionResult>();
        var challenge = new CopilotAuthResolver.CopilotLoginChallenge(
                "device-code",
                "user-code",
                "https://github.com/login/device",
                2,
                60,
                Long.MAX_VALUE,
                "read:user",
                null
        );
        Thread refreshThread = Thread.startVirtualThread(subject::resolveBearerTokenOrNull);
        Thread loginThread = null;

        try {
            assertThat(refreshStarted.await(2, TimeUnit.SECONDS)).isTrue();
            loginThread = Thread.startVirtualThread(() -> result.set(subject.completeLogin(challenge, () -> {
                if (loginExchangeCompleted.get() && checksAfterExchange.incrementAndGet() == 1) {
                    finalCancellationCheck.countDown();
                }
                return cancelled.get();
            })));
            assertThat(finalCancellationCheck.await(2, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
        } finally {
            cancelled.set(true);
            releaseRefresh.countDown();
            refreshThread.join(TimeUnit.SECONDS.toMillis(2));
            if (loginThread != null) {
                loginThread.interrupt();
                loginThread.join(TimeUnit.SECONDS.toMillis(2));
            }
        }

        assertThat(refreshThread.isAlive()).isFalse();
        assertThat(loginThread).isNotNull();
        assertThat(loginThread.isAlive()).isFalse();
        assertThat(result.get().success()).isFalse();
        assertThat(tokenFile)
                .content()
                .contains("tid=refreshed-copilot-session-token")
                .doesNotContain("tid=login-copilot-session-token");
    }

    @Test
    @DisplayName("Provider device-code expiry is preserved exactly")
    void beginLogin_whenProviderReturnsShortExpiry_preservesExpiry() {
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-client-id");
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "device_code": "device-code-123",
                  "user_code": "ABCD-1234",
                  "verification_uri": "https://github.com/login/device",
                  "expires_in": 10,
                  "interval": 2
                }
                """);
        try {
            when(httpClient.send(
                    any(HttpRequest.class),
                    org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
            )).thenReturn(response);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        var subject = new CopilotAuthResolver(tempDir.resolve("short-expiry-home"), emptyMap(), httpClient);

        CopilotAuthResolver.CopilotLoginChallenge challenge = subject.beginLogin(() -> false, ignored -> true);

        assertThat(challenge.expiresInSeconds()).isEqualTo(10);
        assertThat(challenge.timeoutDeadlineNanos() - System.nanoTime())
                .isPositive()
                .isLessThanOrEqualTo(TimeUnit.SECONDS.toNanos(10));
    }

    @Test
    @DisplayName("Device polling does not sleep past the provider expiry")
    void completeLogin_whenPollingIntervalExceedsRemainingExpiry_stopsAtProviderDeadline() throws Exception {
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-client-id");
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> pendingResponse = mock(HttpResponse.class);
        when(pendingResponse.statusCode()).thenReturn(200);
        when(pendingResponse.body()).thenReturn("{\"error\":\"authorization_pending\"}");
        when(httpClient.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        )).thenReturn(pendingResponse);
        var subject = new CopilotAuthResolver(tempDir.resolve("poll-expiry-home"), emptyMap(), httpClient);
        var challenge = new CopilotAuthResolver.CopilotLoginChallenge(
                "device-code",
                "user-code",
                "https://github.com/login/device",
                60,
                1,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(1),
                "read:user",
                null
        );
        long startedAtNanos = System.nanoTime();

        CopilotAuthResolver.CopilotAuthActionResult result = subject.completeLogin(challenge, () -> false);

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("timed out");
        assertThat(elapsedMillis).isLessThan(3_000L);
    }

    @Test
    @DisplayName("Device poll responses arriving after expiry are rejected")
    void completeLogin_whenAccessTokenResponseArrivesAfterDeadline_returnsTimeout() throws Exception {
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-client-id");
        var requestRef = new AtomicReference<HttpRequest>();
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"access_token\":\"DUMMY_GITHUB_ACCESS_TOKEN_AFTER_EXPIRY\"}");
        long timeoutDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        when(httpClient.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        )).thenAnswer(invocation -> {
            requestRef.set(invocation.getArgument(0));
            while (System.nanoTime() < timeoutDeadlineNanos) {
                Thread.onSpinWait();
            }
            return response;
        });
        var subject = new CopilotAuthResolver(tempDir.resolve("late-poll-home"), emptyMap(), httpClient);
        var challenge = new CopilotAuthResolver.CopilotLoginChallenge(
                "device-code",
                "user-code",
                "https://github.com/login/device",
                2,
                1,
                timeoutDeadlineNanos,
                "read:user",
                null
        );

        CopilotAuthResolver.CopilotAuthActionResult result = subject.completeLogin(challenge, () -> false);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("timed out");
        assertThat(requestRef.get().timeout()).hasValueSatisfying(timeout ->
                assertThat(timeout).isLessThanOrEqualTo(Duration.ofSeconds(1))
        );
    }

    @Test
    @DisplayName("OAuth callback exceptions cannot expose challenge secrets in logs or results")
    void login_whenChallengeConsumerIncludesSecretsInException_sanitizesDiagnostics() {
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-client-id");
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "device_code": "sentinel-device-code",
                  "user_code": "sentinel-user-code",
                  "verification_uri": "https://github.example/login?code=sentinel-user-code",
                  "expires_in": 600,
                  "interval": 2
                }
                """);
        try {
            when(httpClient.send(
                    any(HttpRequest.class),
                    org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
            )).thenReturn(response);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        var subject = new CopilotAuthResolver(
                tempDir.resolve("diagnostic-home"),
                emptyMap(),
                httpClient,
                mock(CopilotAuthResolver.UserPromptActions.class)
        );
        Logger logger = (Logger) LoggerFactory.getLogger(CopilotAuthResolver.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        CopilotAuthResolver.CopilotAuthActionResult result;
        try {
            result = subject.login(
                    () -> false,
                    ignored -> true,
                    challenge -> {
                        throw new IllegalStateException("sentinel %s %s %s".formatted(
                                challenge.deviceCode(),
                                challenge.userCode(),
                                challenge.verificationUri()
                        ));
                    }
            );
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList().toString();
        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("GitHub Copilot login failed.");
        assertThat("%s %s".formatted(logs, result))
                .doesNotContain("sentinel-device-code", "sentinel-user-code", "github.example");
    }

    @Test
    @DisplayName("A second explicit auth operation is rejected until the active login releases its guard")
    void login_whenChallengeConsumerIsPending_rejectsConcurrentLogoutAndReleasesGuard() throws Exception {
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-client-id");
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
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
        )).thenReturn(response);
        var challengeReceived = new CountDownLatch(1);
        var releaseChallenge = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var loginResult = new AtomicReference<CopilotAuthResolver.CopilotAuthActionResult>();
        var subject = new CopilotAuthResolver(tempDir.resolve("operation-guard-home"), emptyMap(), httpClient);
        Thread loginThread = Thread.startVirtualThread(() -> loginResult.set(subject.login(
                cancelled::get,
                ignored -> true,
                challenge -> {
                    challengeReceived.countDown();
                    try {
                        releaseChallenge.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
        )));

        try {
            assertThat(challengeReceived.await(2, TimeUnit.SECONDS)).isTrue();

            CopilotAuthResolver.CopilotAuthActionResult concurrentResult = subject.logout();

            assertThat(concurrentResult.success()).isFalse();
            assertThat(concurrentResult.message()).contains("already in progress");
        } finally {
            cancelled.set(true);
            releaseChallenge.countDown();
            loginThread.join(TimeUnit.SECONDS.toMillis(2));
        }

        assertThat(loginThread.isAlive()).isFalse();
        assertThat(loginResult.get().success()).isFalse();
        assertThat(subject.logout().success()).isTrue();
    }

    @Test
    @DisplayName("Login challenge diagnostics mask device codes and verification URLs")
    void toString_whenLoginChallengeIsRendered_masksOAuthSecrets() {
        var challenge = new CopilotAuthResolver.CopilotLoginChallenge(
                "secret-device-code",
                "secret-user-code",
                "https://github.example/login?user_code=secret-user-code",
                5,
                900,
                Long.MAX_VALUE,
                "read:user",
                null
        );

        assertThat(challenge.toString())
                .doesNotContain("secret-device-code", "secret-user-code", "github.example")
                .contains("deviceCode=<masked>", "userCode=<masked>", "verificationUri=<masked>");
    }

    @Test
    @DisplayName("Logout waits for an in-flight refresh and leaves the auth file deleted")
    void logout_whenTokenRefreshIsInFlight_deletesRefreshedFileAfterRefreshCompletes() throws Exception {
        Path userHome = tempDir.resolve("refresh-logout-home");
        Path tokenFile = userHome.resolve(".config/chat4j/copilot-auth.json");
        Files.createDirectories(tokenFile.getParent());
        Files.writeString(tokenFile, """
                {
                  "accessToken": "tid=expired-copilot-session-token;exp=1",
                  "refreshToken": "gho_refresh_token_for_locking_test",
                  "expiresAtEpochMs": 1,
                  "oauthScopes": "read:user"
                }
                """);
        var refreshStarted = new CountDownLatch(1);
        var releaseRefresh = new CountDownLatch(1);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"token\":\"tid=refreshed-copilot-session-token;exp=4070908800\",\"expires_at\":4070908800}");
        when(httpClient.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        )).thenAnswer(invocation -> {
            refreshStarted.countDown();
            releaseRefresh.await(2, TimeUnit.SECONDS);
            return response;
        });
        var subject = new CopilotAuthResolver(userHome, emptyMap(), httpClient);
        ReentrantLock mutationLock = authFileMutationLock(subject);
        var logoutResult = new AtomicReference<CopilotAuthResolver.CopilotAuthActionResult>();
        var logoutFinished = new CountDownLatch(1);
        Thread refreshThread = Thread.startVirtualThread(subject::resolveBearerTokenOrNull);
        Thread logoutThread = null;

        try {
            assertThat(refreshStarted.await(2, TimeUnit.SECONDS)).isTrue();
            logoutThread = Thread.startVirtualThread(() -> {
                try {
                    logoutResult.set(subject.logout());
                } finally {
                    logoutFinished.countDown();
                }
            });
            awaitQueuedOnLock(mutationLock, logoutThread);
            assertThat(logoutFinished.getCount()).isEqualTo(1L);
        } finally {
            releaseRefresh.countDown();
            refreshThread.join(TimeUnit.SECONDS.toMillis(2));
            if (logoutThread != null) {
                logoutThread.join(TimeUnit.SECONDS.toMillis(2));
                if (logoutThread.isAlive()) {
                    logoutThread.interrupt();
                    logoutThread.join(TimeUnit.SECONDS.toMillis(2));
                }
            }
        }

        assertThat(refreshThread.isAlive()).isFalse();
        assertThat(logoutThread).isNotNull();
        assertThat(logoutThread.isAlive()).isFalse();
        assertThat(logoutResult.get().success()).isTrue();
        assertThat(tokenFile).doesNotExist();
    }

    private ReentrantLock authFileMutationLock(CopilotAuthResolver subject) throws Exception {
        Field field = CopilotAuthResolver.class.getDeclaredField("authFileMutationLock");
        field.setAccessible(true);
        return (ReentrantLock) field.get(subject);
    }

    private void awaitQueuedOnLock(ReentrantLock lock, Thread thread) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!lock.hasQueuedThread(thread) && thread.isAlive() && System.nanoTime() < deadlineNanos) {
            Thread.onSpinWait();
        }
        assertThat(lock.hasQueuedThread(thread)).isTrue();
    }

    @Test
    @DisplayName("Cancelled logout reports the correct operation")
    void logout_whenCancellationIsRequested_reportsLogoutCancellation() {
        var subject = new CopilotAuthResolver(
                tempDir.resolve("cancelled-logout-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        );

        CopilotAuthResolver.CopilotAuthActionResult result = subject.logout(() -> true);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("GitHub Copilot logout cancelled.");
    }

    @Test
    @DisplayName("Logout reports deletion failure without treating an unsafe target as absent")
    void logout_whenAuthPathIsNonEmptyDirectory_returnsFailureAndPreservesTarget() throws Exception {
        Path userHome = tempDir.resolve("logout-failure-home");
        Path tokenPath = userHome.resolve(".config/chat4j/copilot-auth.json");
        Files.createDirectories(tokenPath);
        Files.writeString(tokenPath.resolve("preserve.txt"), "not an auth file");
        var subject = new CopilotAuthResolver(userHome, emptyMap(), HttpClient.newHttpClient());

        CopilotAuthResolver.CopilotAuthActionResult result = subject.logout();

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("Unable to delete the GitHub Copilot login session.");
        assertThat(tokenPath.resolve("preserve.txt")).exists();
    }

    @Test
    @DisplayName("Resolver accepts stored session even when oauthScopes metadata is absent")
    void resolveStatus_whenStoredTokenHasNoOauthScopesMetadata_returnsAuthorized() throws Exception {
        Path userHome = tempDir.resolve("home");
        Path tokenFile = userHome.resolve(".config/chat4j/copilot-auth.json");
        Files.createDirectories(tokenFile.getParent());
        Files.writeString(tokenFile, """
                {
                  "accessToken": "tid=dummy-copilot-session-token-for-tests;exp=4070908800",
                  "updatedAtEpochMs": 1776600000000,
                  "source": "Chat4J OAuth"
                }
                """);

        var subject = new CopilotAuthResolver(userHome, emptyMap(), HttpClient.newHttpClient());

        var status = subject.resolveStatus();

        assertThat(status.authorized()).isTrue();
        assertThat(status.message()).contains("Authorized via");
    }

    @Test
    @DisplayName("Logout removes Chat4J OAuth token file")
    void logout_whenChat4jTokenExists_deletesStoredToken() throws Exception {
        Path userHome = tempDir.resolve("home");
        Path tokenFile = userHome.resolve(".config/chat4j/copilot-auth.json");
        Files.createDirectories(tokenFile.getParent());
        Files.writeString(tokenFile, """
                {
                  "accessToken": "tid=dummy-copilot-session-token-for-tests;exp=4070908800",
                  "updatedAtEpochMs": 1776600000000,
                  "source": "Chat4J OAuth"
                }
                """);

        var subject = new CopilotAuthResolver(userHome, emptyMap(), HttpClient.newHttpClient());

        CopilotAuthResolver.CopilotAuthActionResult result = subject.logout();

        assertThat(result.success()).isTrue();
        assertThat(subject.resolveBearerTokenOrNull()).isNull();
    }
}
