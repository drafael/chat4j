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

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CodexAuthResolverTest {

    private static final String OAUTH_ISSUER_PROPERTY = "chat4j.codex.oauthIssuer";
    private static final String OAUTH_CLIENT_ID_PROPERTY = "chat4j.codex.oauthClientId";
    private static final String OAUTH_SCOPES_PROPERTY = "chat4j.codex.oauthScopes";
    private static final String OAUTH_REDIRECT_URI_PROPERTY = "chat4j.codex.oauthRedirectUri";
    private static final String OAUTH_CALLBACK_HOST_PROPERTY = "chat4j.codex.oauthCallbackHost";
    private static final String OAUTH_LOGIN_TIMEOUT_SECONDS_PROPERTY = "chat4j.codex.oauthLoginTimeoutSeconds";
    private static final String DUMMY_CODEX_ACCESS_TOKEN = "DUMMY_CODEX_ACCESS_TOKEN_FOR_TESTS";
    private static final Set<String> MANAGED_SYSTEM_PROPERTIES = Set.of(
            OAUTH_ISSUER_PROPERTY,
            OAUTH_CLIENT_ID_PROPERTY,
            OAUTH_SCOPES_PROPERTY,
            OAUTH_REDIRECT_URI_PROPERTY,
            OAUTH_CALLBACK_HOST_PROPERTY,
            OAUTH_LOGIN_TIMEOUT_SECONDS_PROPERTY
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
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");

        var subject = new CodexAuthResolver(userHome, emptyMap(), HttpClient.newHttpClient());

        assertThat(subject.isOAuthClientConfigured()).isTrue();
    }

    @Test
    @DisplayName("Resolver reports unauthorized when no stored Chat4J token exists")
    void resolveStatus_whenNoStoredToken_returnsUnauthorized() {
        Path userHome = tempDir.resolve("home");
        var subject = new CodexAuthResolver(userHome, emptyMap(), HttpClient.newHttpClient());

        var status = subject.resolveStatus();

        assertThat(status.authorized()).isFalse();
        assertThat(status.message()).contains("Not authorized");
    }

    @Test
    @DisplayName("Expired stored tokens without refresh credentials are rejected")
    void resolveBearerTokenOrNull_whenStoredTokenExpiredWithoutRefreshToken_returnsNull() throws Exception {
        Path userHome = tempDir.resolve("expired-home");
        Path tokenFile = userHome.resolve(".config/chat4j/codex-auth.json");
        Files.createDirectories(tokenFile.getParent());
        Files.writeString(tokenFile, """
                {
                  "accessToken": "DUMMY_CODEX_EXPIRED_ACCESS_TOKEN",
                  "expiresAtEpochMs": 1
                }
                """);
        var subject = new CodexAuthResolver(userHome, emptyMap(), HttpClient.newHttpClient());

        assertThat(subject.resolveBearerTokenOrNull()).isNull();
    }

    @Test
    @DisplayName("Expired JWTs without stored expiry metadata are rejected")
    void resolveBearerTokenOrNull_whenJwtExpiryIsPastAndStoredExpiryMissing_returnsNull() throws Exception {
        Path userHome = tempDir.resolve("expired-jwt-home");
        Path tokenFile = userHome.resolve(".config/chat4j/codex-auth.json");
        Files.createDirectories(tokenFile.getParent());
        Files.writeString(tokenFile, """
                {
                  "accessToken": "eyJhbGciOiJub25lIn0.eyJleHAiOjF9.signature"
                }
                """);
        var subject = new CodexAuthResolver(userHome, emptyMap(), HttpClient.newHttpClient());

        assertThat(subject.resolveBearerTokenOrNull()).isNull();
        assertThat(subject.resolveStatus().authorized()).isFalse();
    }

    @Test
    @DisplayName("JWT upgrades preserve refresh credentials for later renewal")
    void resolveBearerTokenOrNull_whenJwtIsUpgraded_preservesRefreshToken() throws Exception {
        Path userHome = tempDir.resolve("jwt-upgrade-home");
        Path tokenFile = userHome.resolve(".config/chat4j/codex-auth.json");
        Files.createDirectories(tokenFile.getParent());
        Files.writeString(tokenFile, """
                {
                  "accessToken": "eyJhbGciOiJub25lIn0.eyJleHAiOjQxMDI0NDQ4MDB9.signature",
                  "refreshToken": "refresh-token-that-must-survive-upgrade",
                  "expiresAtEpochMs": 4100000000000,
                  "oauthScopes": "openid offline_access"
                }
                """);
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"access_token\":\"DUMMY_CODEX_UPGRADED_ACCESS_TOKEN\"}");
        when(httpClient.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        )).thenReturn(response);
        var subject = new CodexAuthResolver(userHome, emptyMap(), httpClient);

        assertThat(subject.resolveBearerTokenOrNull()).isEqualTo("DUMMY_CODEX_UPGRADED_ACCESS_TOKEN");
        assertThat(tokenFile)
                .content()
                .contains("refresh-token-that-must-survive-upgrade")
                .contains("DUMMY_CODEX_UPGRADED_ACCESS_TOKEN")
                .contains("\"expiresAtEpochMs\" : 4100000000000");
    }

    @Test
    @DisplayName("Begin login creates a PKCE authorization URL")
    void beginLogin_whenConfigured_returnsPkceChallenge() throws Exception {
        Path userHome = tempDir.resolve("home");
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
        System.setProperty(OAUTH_SCOPES_PROPERTY, "openid profile email offline_access");

        var subject = new CodexAuthResolver(userHome, emptyMap(), HttpClient.newHttpClient());

        CodexAuthResolver.CodexLoginChallenge challenge = subject.beginLogin();

        assertThat(challenge.authorizationUri())
                .contains("response_type=code")
                .contains("client_id=chat4j-codex-client-id")
                .contains("code_challenge=")
                .contains("code_challenge_method=S256")
                .contains("codex_cli_simplified_flow=true");
        assertThat(challenge.codeVerifier()).isNotBlank();
        assertThat(challenge.state()).isNotBlank();

    }

    @Test
    @DisplayName("Begin login binds callback listener to redirect URI host by default")
    void beginLogin_whenRedirectUriConfigured_usesRedirectUriHostAsCallbackHost() {
        Path userHome = tempDir.resolve("home");
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
        System.setProperty(OAUTH_REDIRECT_URI_PROPERTY, "http://localhost:1459/auth/callback");

        var subject = new CodexAuthResolver(userHome, emptyMap(), HttpClient.newHttpClient());

        CodexAuthResolver.CodexLoginChallenge challenge = subject.beginLogin();

        assertThat(challenge.redirectUri()).isEqualTo("http://localhost:1459/auth/callback");
        assertThat(challenge.callbackHost()).isEqualTo("localhost");
    }

    @Test
    @DisplayName("Callback listener completes with browser redirect input")
    void startCallbackWait_whenBrowserRedirectArrives_completesFuture() throws Exception {
        Path userHome = tempDir.resolve("home");
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
        System.setProperty(OAUTH_REDIRECT_URI_PROPERTY, "http://localhost:%d/auth/callback".formatted(freePort()));

        var subject = new CodexAuthResolver(userHome, emptyMap(), HttpClient.newHttpClient());
        CodexAuthResolver.CodexLoginChallenge challenge = subject.beginLogin();
        CodexAuthResolver.CodexCallbackWait callbackWait = subject.startCallbackWait(challenge);

        try {
            URI callbackUri = URI.create("%s?code=browser-code-123&state=%s".formatted(challenge.redirectUri(), challenge.state()));
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(callbackUri).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(callbackWait.listening()).isTrue();
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(callbackWait.callbackInputFuture().get(2, TimeUnit.SECONDS))
                    .contains("code=browser-code-123")
                    .contains("state=%s".formatted(challenge.state()));
        } finally {
            callbackWait.close();
        }
    }

    @Test
    @DisplayName("Callback listener rejects browser redirects without state")
    void startCallbackWait_whenBrowserRedirectOmitsState_returnsBadRequest() throws Exception {
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
        System.setProperty(OAUTH_REDIRECT_URI_PROPERTY, "http://localhost:%d/auth/callback".formatted(freePort()));
        var subject = new CodexAuthResolver(tempDir.resolve("callback-state-home"), emptyMap(), HttpClient.newHttpClient());
        CodexAuthResolver.CodexLoginChallenge challenge = subject.beginLogin();
        CodexAuthResolver.CodexCallbackWait callbackWait = subject.startCallbackWait(challenge);

        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("%s?code=browser-code-123".formatted(challenge.redirectUri())))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(callbackWait.callbackInputFuture()).isNotDone();
        } finally {
            callbackWait.close();
        }
    }

    @Test
    @DisplayName("Login accepts manual redirect input and stores token")
    void login_whenManualInputProvided_storesToken() throws Exception {
        Path userHome = tempDir.resolve("home");
        AtomicReference<String> tokenRequestBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth/token", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            tokenRequestBody.compareAndSet(null, body);

            byte[] payload = """
                    {
                      "access_token": "DUMMY_CODEX_ACCESS_TOKEN_FOR_TESTS",
                      "refresh_token": "refresh-token-123",
                      "expires_in": 3600
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            System.setProperty(OAUTH_ISSUER_PROPERTY, "http://127.0.0.1:%d".formatted(port));
            System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
            System.setProperty(OAUTH_LOGIN_TIMEOUT_SECONDS_PROPERTY, "1");
            System.setProperty(OAUTH_REDIRECT_URI_PROPERTY, "http://localhost:1459/auth/callback");

            var subject = new CodexAuthResolver(userHome, emptyMap(), HttpClient.newHttpClient());

            CodexAuthResolver.CodexAuthActionResult result = subject.login(
                    () -> false,
                    challenge -> "http://localhost:1459/auth/callback?code=auth-code-123&state=%s"
                            .formatted(challenge.state())
            );

            assertThat(result.success()).isTrue();
            assertThat(subject.resolveBearerTokenOrNull())
                    .isEqualTo(DUMMY_CODEX_ACCESS_TOKEN);
            assertThat(subject.resolveStatus().authorized()).isTrue();
            assertThat(tokenRequestBody.get())
                    .contains("grant_type=authorization_code")
                    .contains("client_id=chat4j-codex-client-id")
                    .contains("code=auth-code-123")
                    .contains("code_verifier=");

            Path tokenFile = userHome.resolve(".config/chat4j/codex-auth.json");
            if (tokenFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                assertThat(Files.getPosixFilePermissions(tokenFile))
                        .isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Cancelling a blocked token exchange prevents credential persistence")
    void completeLoginWithAuthorizationInput_whenExchangeIsCancelled_stopsWorkerWithoutWritingToken() throws Exception {
        Path userHome = tempDir.resolve("home");
        var requestStarted = new CountDownLatch(1);
        var releaseRequest = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth/token", exchange -> {
            requestStarted.countDown();
            try {
                releaseRequest.await(5, TimeUnit.SECONDS);
                byte[] payload = """
                        {"access_token":"DUMMY_CODEX_ACCESS_TOKEN_FOR_TESTS","expires_in":3600}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        var cancelled = new AtomicBoolean();
        var result = new AtomicReference<CodexAuthResolver.CodexAuthActionResult>();
        Thread worker = null;
        try {
            System.setProperty(OAUTH_ISSUER_PROPERTY, "http://127.0.0.1:%d".formatted(server.getAddress().getPort()));
            System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
            System.setProperty(OAUTH_REDIRECT_URI_PROPERTY, "http://localhost:1459/auth/callback");
            var subject = new CodexAuthResolver(userHome, emptyMap(), HttpClient.newHttpClient());
            CodexAuthResolver.CodexLoginChallenge challenge = subject.beginLogin();
            String input = "%s?code=auth-code-123&state=%s".formatted(challenge.redirectUri(), challenge.state());
            worker = Thread.startVirtualThread(() -> result.set(
                    subject.completeLoginWithAuthorizationInput(challenge, input, cancelled::get)
            ));

            assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
            worker.interrupt();
            worker.join(TimeUnit.SECONDS.toMillis(2));

            assertThat(worker.isAlive()).isFalse();
            assertThat(worker.isInterrupted()).isTrue();
            assertThat(result.get().success()).isFalse();
            assertThat(userHome.resolve(".config/chat4j/codex-auth.json")).doesNotExist();
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
    @DisplayName("Token exchange requests are bounded by the remaining login challenge time")
    void completeLogin_whenChallengeHasShortDeadline_boundsEveryTokenRequest() throws Exception {
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
        System.setProperty(OAUTH_LOGIN_TIMEOUT_SECONDS_PROPERTY, "2");
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> tokenResponse = mock(HttpResponse.class);
        when(tokenResponse.statusCode()).thenReturn(200);
        when(tokenResponse.body()).thenReturn("""
                {"access_token":"DUMMY_CODEX_LOGIN_ACCESS_TOKEN","expires_in":3600}
                """);
        HttpResponse<String> exchangeFailure = mock(HttpResponse.class);
        when(exchangeFailure.statusCode()).thenReturn(400);
        var requestCount = new AtomicInteger();
        List<Duration> requestTimeouts = new ArrayList<>();
        when(httpClient.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        )).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            requestTimeouts.add(request.timeout().orElseThrow());
            return requestCount.incrementAndGet() == 1 ? tokenResponse : exchangeFailure;
        });
        var subject = new CodexAuthResolver(tempDir.resolve("bounded-exchange-home"), emptyMap(), httpClient);
        CodexAuthResolver.CodexLoginChallenge challenge = subject.beginLogin();
        String input = "%s?code=auth-code-123&state=%s".formatted(challenge.redirectUri(), challenge.state());

        CodexAuthResolver.CodexAuthActionResult result = subject.completeLoginWithAuthorizationInput(
                challenge,
                input,
                () -> false
        );

        assertThat(result.success()).isTrue();
        assertThat(requestTimeouts).hasSize(3).allSatisfy(timeout -> {
            assertThat(timeout).isPositive();
            assertThat(timeout).isLessThanOrEqualTo(Duration.ofSeconds(2));
        });
    }

    @Test
    @DisplayName("Token exchange that exhausts the challenge deadline reports a login timeout")
    void completeLogin_whenTokenExchangeExhaustsDeadline_reportsTimeout() throws Exception {
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
        HttpClient httpClient = mock(HttpClient.class);
        var deadline = new AtomicLong();
        when(httpClient.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        )).thenAnswer(invocation -> {
            awaitDeadline(deadline.get());
            throw new HttpTimeoutException("request timed out");
        });
        var subject = new CodexAuthResolver(tempDir.resolve("exchange-timeout-home"), emptyMap(), httpClient);
        CodexAuthResolver.CodexLoginChallenge activeChallenge = subject.beginLogin();
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
        deadline.set(deadlineNanos);
        var expiringChallenge = new CodexAuthResolver.CodexLoginChallenge(
                activeChallenge.codeVerifier(),
                activeChallenge.state(),
                activeChallenge.authorizationUri(),
                activeChallenge.redirectUri(),
                activeChallenge.callbackHost(),
                activeChallenge.timeoutSeconds(),
                deadlineNanos,
                activeChallenge.oauthScopes()
        );
        String input = "%s?code=auth-code-123&state=%s"
                .formatted(expiringChallenge.redirectUri(), expiringChallenge.state());

        CodexAuthResolver.CodexAuthActionResult result = subject.completeLoginWithAuthorizationInput(
                expiringChallenge,
                input,
                () -> false
        );

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("OpenAI Codex login timed out.");
    }

    @Test
    @DisplayName("Challenge expiry while publication waits for the file lock prevents credential storage")
    void completeLogin_whenChallengeExpiresWhileWaitingForAuthFileLock_doesNotPublishToken() throws Exception {
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
        Path userHome = tempDir.resolve("expired-publication-home");
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> tokenResponse = mock(HttpResponse.class);
        when(tokenResponse.statusCode()).thenReturn(200);
        when(tokenResponse.body()).thenReturn("""
                {"access_token":"DUMMY_CODEX_LOGIN_ACCESS_TOKEN","expires_in":3600}
                """);
        HttpResponse<String> exchangeFailure = mock(HttpResponse.class);
        when(exchangeFailure.statusCode()).thenReturn(400);
        var requestCount = new AtomicInteger();
        when(httpClient.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        )).thenAnswer(invocation -> requestCount.incrementAndGet() == 1 ? tokenResponse : exchangeFailure);
        var subject = new CodexAuthResolver(userHome, emptyMap(), httpClient);
        CodexAuthResolver.CodexLoginChallenge activeChallenge = subject.beginLogin();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        var expiringChallenge = new CodexAuthResolver.CodexLoginChallenge(
                activeChallenge.codeVerifier(),
                activeChallenge.state(),
                activeChallenge.authorizationUri(),
                activeChallenge.redirectUri(),
                activeChallenge.callbackHost(),
                activeChallenge.timeoutSeconds(),
                deadlineNanos,
                activeChallenge.oauthScopes()
        );
        String input = "%s?code=auth-code-123&state=%s"
                .formatted(expiringChallenge.redirectUri(), expiringChallenge.state());
        ReentrantLock mutationLock = authFileMutationLock(subject);
        var result = new AtomicReference<CodexAuthResolver.CodexAuthActionResult>();
        mutationLock.lock();
        Thread worker = Thread.startVirtualThread(() -> result.set(
                subject.completeLoginWithAuthorizationInput(expiringChallenge, input, () -> false)
        ));

        try {
            awaitQueuedOnLock(mutationLock, worker);
            awaitDeadline(deadlineNanos);
        } finally {
            mutationLock.unlock();
            worker.join(TimeUnit.SECONDS.toMillis(2));
        }

        assertThat(worker.isAlive()).isFalse();
        assertThat(result.get().success()).isFalse();
        assertThat(result.get().message()).contains("timed out");
        assertThat(userHome.resolve(".config/chat4j/codex-auth.json")).doesNotExist();
    }

    @Test
    @DisplayName("Cancellation while final publication is waiting does not replace stored credentials")
    void completeLogin_whenCancelledWhileWaitingForAuthFileLock_doesNotPublishLoginToken() throws Exception {
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
        Path userHome = tempDir.resolve("cancelled-publication-home");
        Path tokenFile = userHome.resolve(".config/chat4j/codex-auth.json");
        Files.createDirectories(tokenFile.getParent());
        Files.writeString(tokenFile, """
                {
                  "accessToken": "DUMMY_CODEX_EXPIRED_ACCESS_TOKEN",
                  "refreshToken": "refresh-token-for-publication-test",
                  "expiresAtEpochMs": 1,
                  "oauthScopes": "openid"
                }
                """);
        var refreshStarted = new CountDownLatch(1);
        var releaseRefresh = new CountDownLatch(1);
        var authorizationExchangeCompleted = new AtomicBoolean();
        var requestCount = new AtomicInteger();
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> refreshFailure = mock(HttpResponse.class);
        when(refreshFailure.statusCode()).thenReturn(401);
        HttpResponse<String> loginTokenResponse = mock(HttpResponse.class);
        when(loginTokenResponse.statusCode()).thenReturn(200);
        when(loginTokenResponse.body()).thenReturn("""
                {"access_token":"DUMMY_CODEX_LOGIN_ACCESS_TOKEN","expires_in":3600}
                """);
        HttpResponse<String> exchangeFailure = mock(HttpResponse.class);
        when(exchangeFailure.statusCode()).thenReturn(400);
        when(httpClient.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        )).thenAnswer(invocation -> {
            int requestNumber = requestCount.incrementAndGet();
            if (requestNumber == 1) {
                refreshStarted.countDown();
                releaseRefresh.await(2, TimeUnit.SECONDS);
                return refreshFailure;
            }
            if (requestNumber == 2) {
                authorizationExchangeCompleted.set(true);
                return loginTokenResponse;
            }
            return exchangeFailure;
        });
        var subject = new CodexAuthResolver(userHome, emptyMap(), httpClient);
        var cancelled = new AtomicBoolean();
        var finalCancellationCheck = new CountDownLatch(1);
        var checksAfterExchange = new AtomicInteger();
        var result = new AtomicReference<CodexAuthResolver.CodexAuthActionResult>();
        Thread refreshThread = Thread.startVirtualThread(subject::resolveBearerTokenOrNull);
        Thread loginThread = null;

        try {
            assertThat(refreshStarted.await(2, TimeUnit.SECONDS)).isTrue();
            CodexAuthResolver.CodexLoginChallenge challenge = subject.beginLogin();
            String input = "%s?code=auth-code-123&state=%s".formatted(challenge.redirectUri(), challenge.state());
            loginThread = Thread.startVirtualThread(() -> result.set(
                    subject.completeLoginWithAuthorizationInput(challenge, input, () -> {
                        if (authorizationExchangeCompleted.get() && checksAfterExchange.incrementAndGet() == 2) {
                            finalCancellationCheck.countDown();
                        }
                        return cancelled.get();
                    })
            ));
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
                .contains("DUMMY_CODEX_EXPIRED_ACCESS_TOKEN")
                .doesNotContain("DUMMY_CODEX_LOGIN_ACCESS_TOKEN");
    }

    @Test
    @DisplayName("Authorization input exceptions cannot expose challenge secrets in logs or results")
    void login_whenInputProviderIncludesSecretsInException_sanitizesDiagnostics() {
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
        var subject = new CodexAuthResolver(tempDir.resolve("diagnostic-home"), emptyMap(), HttpClient.newHttpClient());
        Logger logger = (Logger) LoggerFactory.getLogger(CodexAuthResolver.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        CodexAuthResolver.CodexAuthActionResult result;
        try {
            result = subject.login(
                    () -> false,
                    challenge -> {
                        throw new IllegalStateException("sentinel %s %s %s".formatted(
                                challenge.authorizationUri(),
                                challenge.state(),
                                challenge.codeVerifier()
                        ));
                    }
            );
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList().toString();
        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("OpenAI Codex login failed.");
        assertThat("%s %s".formatted(logs, result))
                .doesNotContain("code_challenge=", "state=", "sentinel");
    }

    @Test
    @DisplayName("Callback URLs without state are rejected before token exchange")
    void completeLoginWithAuthorizationInput_whenCallbackStateMissing_returnsFailure() {
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
        HttpClient httpClient = mock(HttpClient.class);
        var subject = new CodexAuthResolver(tempDir.resolve("missing-state-home"), emptyMap(), httpClient);
        CodexAuthResolver.CodexLoginChallenge challenge = subject.beginLogin();

        CodexAuthResolver.CodexAuthActionResult result = subject.completeLoginWithAuthorizationInput(
                challenge,
                "%s?code=authorization-code".formatted(challenge.redirectUri()),
                () -> false
        );

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("state is missing");
        verifyNoMoreInteractions(httpClient);
    }

    @Test
    @DisplayName("Callback URLs with mismatched state are rejected before token exchange")
    void completeLoginWithAuthorizationInput_whenCallbackStateMismatches_returnsFailure() {
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
        HttpClient httpClient = mock(HttpClient.class);
        var subject = new CodexAuthResolver(tempDir.resolve("mismatched-state-home"), emptyMap(), httpClient);
        CodexAuthResolver.CodexLoginChallenge challenge = subject.beginLogin();

        CodexAuthResolver.CodexAuthActionResult result = subject.completeLoginWithAuthorizationInput(
                challenge,
                "%s?code=authorization-code&state=wrong-state".formatted(challenge.redirectUri()),
                () -> false
        );

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("state mismatch");
        verifyNoMoreInteractions(httpClient);
    }

    @Test
    @DisplayName("Callback error URLs are not treated as raw authorization codes")
    void completeLoginWithAuthorizationInput_whenCallbackContainsOnlyError_returnsFailure() {
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
        HttpClient httpClient = mock(HttpClient.class);
        var subject = new CodexAuthResolver(tempDir.resolve("callback-error-home"), emptyMap(), httpClient);
        CodexAuthResolver.CodexLoginChallenge challenge = subject.beginLogin();

        CodexAuthResolver.CodexAuthActionResult result = subject.completeLoginWithAuthorizationInput(
                challenge,
                "%s?error=access_denied".formatted(challenge.redirectUri()),
                () -> false
        );

        assertThat(result.success()).isFalse();
        verifyNoMoreInteractions(httpClient);
    }

    @Test
    @DisplayName("Malformed callback URLs are not treated as raw authorization codes")
    void completeLoginWithAuthorizationInput_whenCallbackUrlIsMalformed_returnsFailure() {
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
        HttpClient httpClient = mock(HttpClient.class);
        var subject = new CodexAuthResolver(tempDir.resolve("malformed-callback-home"), emptyMap(), httpClient);
        CodexAuthResolver.CodexLoginChallenge challenge = subject.beginLogin();

        CodexAuthResolver.CodexAuthActionResult malformedHierarchicalUrl = subject.completeLoginWithAuthorizationInput(
                challenge,
                "https://callback.example/%ZZ",
                () -> false
        );
        CodexAuthResolver.CodexAuthActionResult malformedSchemeUrl = subject.completeLoginWithAuthorizationInput(
                challenge,
                "http:%ZZ",
                () -> false
        );

        assertThat(malformedHierarchicalUrl.success()).isFalse();
        assertThat(malformedSchemeUrl.success()).isFalse();
        verifyNoMoreInteractions(httpClient);
    }

    @Test
    @DisplayName("Expired login challenges cannot exchange authorization codes")
    void completeLoginWithAuthorizationInput_whenChallengeExpired_returnsTimeoutWithoutExchange() {
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
        HttpClient httpClient = mock(HttpClient.class);
        var subject = new CodexAuthResolver(tempDir.resolve("expired-challenge-home"), emptyMap(), httpClient);
        CodexAuthResolver.CodexLoginChallenge activeChallenge = subject.beginLogin();
        var expiredChallenge = new CodexAuthResolver.CodexLoginChallenge(
                activeChallenge.codeVerifier(),
                activeChallenge.state(),
                activeChallenge.authorizationUri(),
                activeChallenge.redirectUri(),
                activeChallenge.callbackHost(),
                activeChallenge.timeoutSeconds(),
                System.nanoTime() - 1L,
                activeChallenge.oauthScopes()
        );

        CodexAuthResolver.CodexAuthActionResult result = subject.completeLoginWithAuthorizationInput(
                expiredChallenge,
                "raw-authorization-code",
                () -> false
        );
        CodexAuthResolver.CodexCallbackWait callbackWait = subject.startCallbackWait(expiredChallenge);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("timed out");
        assertThat(callbackWait.listening()).isFalse();
        assertThat(callbackWait.callbackInputFuture()).isCompletedWithValue("");
        verifyNoMoreInteractions(httpClient);
    }

    @Test
    @DisplayName("Missing authorization input does not expose configured redirect queries")
    void login_whenAuthorizationInputIsMissing_sanitizesFailureResult() {
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
        System.setProperty(
                OAUTH_REDIRECT_URI_PROPERTY,
                "http://localhost:1459/auth/callback?sentinel-secret=must-not-leak"
        );
        var subject = new CodexAuthResolver(tempDir.resolve("missing-input-home"), emptyMap(), HttpClient.newHttpClient());

        CodexAuthResolver.CodexAuthActionResult result = subject.login(() -> false, challenge -> "");

        assertThat(result.success()).isFalse();
        assertThat(result.message())
                .isEqualTo("OpenAI Codex login did not receive an authorization code.")
                .doesNotContain("sentinel-secret", "localhost");
        assertThat(subject.logout().success()).isTrue();
    }

    @Test
    @DisplayName("Raw authorization codes remain accepted without state")
    void completeLoginWithAuthorizationInput_whenRawCodeHasNoState_storesToken() throws Exception {
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"access_token\":\"DUMMY_CODEX_ACCESS_TOKEN_FOR_TESTS\",\"expires_in\":3600}");
        when(httpClient.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        )).thenReturn(response);
        var subject = new CodexAuthResolver(tempDir.resolve("raw-code-home"), emptyMap(), httpClient);
        CodexAuthResolver.CodexLoginChallenge challenge = subject.beginLogin();

        CodexAuthResolver.CodexAuthActionResult result = subject.completeLoginWithAuthorizationInput(
                challenge,
                "raw-authorization-code",
                () -> false
        );

        assertThat(result.success()).isTrue();
        assertThat(subject.resolveBearerTokenOrNull()).isEqualTo(DUMMY_CODEX_ACCESS_TOKEN);
    }

    @Test
    @DisplayName("A second explicit auth operation is rejected until the active login releases its guard")
    void login_whenAuthorizationInputIsPending_rejectsConcurrentLogoutAndReleasesGuard() throws Exception {
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
        var inputRequested = new CountDownLatch(1);
        var releaseInput = new CountDownLatch(1);
        var loginResult = new AtomicReference<CodexAuthResolver.CodexAuthActionResult>();
        var subject = new CodexAuthResolver(tempDir.resolve("operation-guard-home"), emptyMap(), HttpClient.newHttpClient());
        Thread loginThread = Thread.startVirtualThread(() -> loginResult.set(subject.login(
                () -> false,
                challenge -> {
                    inputRequested.countDown();
                    releaseInput.await(2, TimeUnit.SECONDS);
                    return null;
                }
        )));

        try {
            assertThat(inputRequested.await(2, TimeUnit.SECONDS)).isTrue();

            CodexAuthResolver.CodexAuthActionResult concurrentResult = subject.logout();

            assertThat(concurrentResult.success()).isFalse();
            assertThat(concurrentResult.message()).contains("already in progress");
        } finally {
            releaseInput.countDown();
            loginThread.join(TimeUnit.SECONDS.toMillis(2));
        }

        assertThat(loginThread.isAlive()).isFalse();
        assertThat(loginResult.get().success()).isFalse();
        assertThat(subject.logout().success()).isTrue();
    }

    @Test
    @DisplayName("Login challenge diagnostics mask PKCE state and authorization query values")
    void toString_whenChallengeIsRendered_masksOAuthSecrets() {
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
        var subject = new CodexAuthResolver(tempDir.resolve("diagnostics-home"), emptyMap(), HttpClient.newHttpClient());

        CodexAuthResolver.CodexLoginChallenge challenge = subject.beginLogin();
        String rendered = challenge.toString();

        assertThat(rendered)
                .doesNotContain(challenge.codeVerifier())
                .doesNotContain(challenge.state())
                .doesNotContain("code_challenge=", challenge.redirectUri())
                .contains("authorizationUri=<masked>", "redirectUri=<masked>");
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    @DisplayName("Logout waits for an in-flight refresh and leaves the auth file deleted")
    void logout_whenTokenRefreshIsInFlight_deletesRefreshedFileAfterRefreshCompletes() throws Exception {
        Path userHome = tempDir.resolve("refresh-logout-home");
        Path tokenFile = userHome.resolve(".config/chat4j/codex-auth.json");
        Files.createDirectories(tokenFile.getParent());
        Files.writeString(tokenFile, """
                {
                  "accessToken": "DUMMY_CODEX_ACCESS_TOKEN_FOR_TESTS",
                  "refreshToken": "refresh-token-for-locking-test",
                  "expiresAtEpochMs": 1,
                  "oauthScopes": "openid"
                }
                """);
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
        var refreshStarted = new CountDownLatch(1);
        var releaseRefresh = new CountDownLatch(1);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"access_token\":\"DUMMY_CODEX_REFRESHED_ACCESS_TOKEN\",\"expires_in\":3600}");
        when(httpClient.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
        )).thenAnswer(invocation -> {
            refreshStarted.countDown();
            releaseRefresh.await(2, TimeUnit.SECONDS);
            return response;
        });
        var subject = new CodexAuthResolver(userHome, emptyMap(), httpClient);
        ReentrantLock mutationLock = authFileMutationLock(subject);
        var logoutResult = new AtomicReference<CodexAuthResolver.CodexAuthActionResult>();
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

    @Test
    @DisplayName("Cancelled logout reports the correct operation")
    void logout_whenCancellationIsRequested_reportsLogoutCancellation() {
        var subject = new CodexAuthResolver(
                tempDir.resolve("cancelled-logout-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        );

        CodexAuthResolver.CodexAuthActionResult result = subject.logout(() -> true);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("OpenAI Codex logout cancelled.");
    }

    @Test
    @DisplayName("Logout reports deletion failure without treating an unsafe target as absent")
    void logout_whenAuthPathIsNonEmptyDirectory_returnsFailureAndPreservesTarget() throws Exception {
        Path userHome = tempDir.resolve("logout-failure-home");
        Path tokenPath = userHome.resolve(".config/chat4j/codex-auth.json");
        Files.createDirectories(tokenPath);
        Files.writeString(tokenPath.resolve("preserve.txt"), "not an auth file");
        var subject = new CodexAuthResolver(userHome, emptyMap(), HttpClient.newHttpClient());

        CodexAuthResolver.CodexAuthActionResult result = subject.logout();

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("Unable to delete the OpenAI Codex login session.");
        assertThat(tokenPath.resolve("preserve.txt")).exists();
    }

    @Test
    @DisplayName("Resolver accepts stored session even when oauthScopes metadata is absent")
    void resolveStatus_whenStoredTokenHasNoOauthScopesMetadata_returnsAuthorized() throws Exception {
        Path userHome = tempDir.resolve("home");
        Path tokenFile = userHome.resolve(".config/chat4j/codex-auth.json");
        Files.createDirectories(tokenFile.getParent());
        Files.writeString(tokenFile, """
                {
                  "accessToken": "DUMMY_CODEX_ACCESS_TOKEN_FOR_TESTS",
                  "updatedAtEpochMs": 1776600000000,
                  "source": "Chat4J OAuth"
                }
                """);

        var subject = new CodexAuthResolver(userHome, emptyMap(), HttpClient.newHttpClient());

        var status = subject.resolveStatus();

        assertThat(status.authorized()).isTrue();
        assertThat(status.message()).contains("Authorized via");
    }

    private ReentrantLock authFileMutationLock(CodexAuthResolver subject) throws Exception {
        Field field = CodexAuthResolver.class.getDeclaredField("authFileMutationLock");
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

    private void awaitDeadline(long deadlineNanos) throws InterruptedException {
        long remainingNanos;
        while ((remainingNanos = deadlineNanos - System.nanoTime()) > 0L) {
            TimeUnit.NANOSECONDS.sleep(remainingNanos);
        }
    }

    @Test
    @DisplayName("Logout removes Chat4J OAuth token file")
    void logout_whenChat4jTokenExists_deletesStoredToken() throws Exception {
        Path userHome = tempDir.resolve("home");
        Path tokenFile = userHome.resolve(".config/chat4j/codex-auth.json");
        Files.createDirectories(tokenFile.getParent());
        Files.writeString(tokenFile, """
                {
                  "accessToken": "DUMMY_CODEX_ACCESS_TOKEN_FOR_TESTS",
                  "updatedAtEpochMs": 1776600000000,
                  "source": "Chat4J OAuth"
                }
                """);

        var subject = new CodexAuthResolver(userHome, emptyMap(), HttpClient.newHttpClient());

        CodexAuthResolver.CodexAuthActionResult result = subject.logout();

        assertThat(result.success()).isTrue();
        assertThat(subject.resolveBearerTokenOrNull()).isNull();
    }
}
