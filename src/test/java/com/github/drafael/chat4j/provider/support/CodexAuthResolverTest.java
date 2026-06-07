package com.github.drafael.chat4j.provider.support;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class CodexAuthResolverTest {

    private static final String OAUTH_ISSUER_PROPERTY = "chat4j.codex.oauthIssuer";
    private static final String OAUTH_CLIENT_ID_PROPERTY = "chat4j.codex.oauthClientId";
    private static final String OAUTH_SCOPES_PROPERTY = "chat4j.codex.oauthScopes";
    private static final String OAUTH_REDIRECT_URI_PROPERTY = "chat4j.codex.oauthRedirectUri";
    private static final String OAUTH_CALLBACK_HOST_PROPERTY = "chat4j.codex.oauthCallbackHost";
    private static final String OAUTH_LOGIN_TIMEOUT_SECONDS_PROPERTY = "chat4j.codex.oauthLoginTimeoutSeconds";
    private static final String DUMMY_CODEX_ACCESS_TOKEN = "DUMMY_CODEX_ACCESS_TOKEN_FOR_TESTS";

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        System.clearProperty(OAUTH_ISSUER_PROPERTY);
        System.clearProperty(OAUTH_CLIENT_ID_PROPERTY);
        System.clearProperty(OAUTH_SCOPES_PROPERTY);
        System.clearProperty(OAUTH_REDIRECT_URI_PROPERTY);
        System.clearProperty(OAUTH_CALLBACK_HOST_PROPERTY);
        System.clearProperty(OAUTH_LOGIN_TIMEOUT_SECONDS_PROPERTY);
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
    @DisplayName("Begin login creates PKCE authorization URL without triggering browser or clipboard actions")
    void beginLogin_whenConfigured_returnsChallengeWithoutPromptActions() throws Exception {
        Path userHome = tempDir.resolve("home");
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");
        System.setProperty(OAUTH_SCOPES_PROPERTY, "openid profile email offline_access");

        CodexAuthResolver.UserPromptActions promptActions = mock(CodexAuthResolver.UserPromptActions.class);
        var subject = new CodexAuthResolver(userHome, emptyMap(), HttpClient.newHttpClient(), promptActions);

        CodexAuthResolver.CodexLoginChallenge challenge = subject.beginLogin();

        assertThat(challenge.authorizationUri())
                .contains("response_type=code")
                .contains("client_id=chat4j-codex-client-id")
                .contains("code_challenge=")
                .contains("code_challenge_method=S256")
                .contains("codex_cli_simplified_flow=true");
        assertThat(challenge.codeVerifier()).isNotBlank();
        assertThat(challenge.state()).isNotBlank();

        verifyNoMoreInteractions(promptActions);
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
    @DisplayName("Complete login accepts manual redirect input fallback and stores token")
    void completeLogin_whenManualInputProvided_storesToken() throws Exception {
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

            AtomicReference<CodexAuthResolver.CodexLoginChallenge> challengeRef = new AtomicReference<>();
            CodexAuthResolver.UserPromptActions promptActions = new CodexAuthResolver.UserPromptActions() {
                @Override
                public void openBrowser(String authorizationUri) {
                    // no-op in tests
                }

                @Override
                public void copyCodeToClipboard(String text) {
                    // no-op in tests
                }

                @Override
                public String promptForAuthorizationInput(String message, String defaultValue) {
                    CodexAuthResolver.CodexLoginChallenge challenge = challengeRef.get();
                    return "http://localhost:1459/auth/callback?code=auth-code-123&state=%s"
                            .formatted(challenge.state());
                }
            };

            var subject = new CodexAuthResolver(userHome, emptyMap(), HttpClient.newHttpClient(), promptActions);

            CodexAuthResolver.CodexLoginChallenge challenge = subject.beginLogin();
            challengeRef.set(challenge);

            CodexAuthResolver.CodexAuthActionResult result = subject.completeLogin(challenge);

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

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
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
