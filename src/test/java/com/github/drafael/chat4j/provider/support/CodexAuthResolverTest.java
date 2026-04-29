package com.github.drafael.chat4j.provider.support;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class CodexAuthResolverTest {

    private static final String OAUTH_ISSUER_PROPERTY = "chat4j.codex.oauthIssuer";
    private static final String OAUTH_CLIENT_ID_PROPERTY = "chat4j.codex.oauthClientId";
    private static final String OAUTH_SCOPES_PROPERTY = "chat4j.codex.oauthScopes";
    private static final String OAUTH_REDIRECT_URI_PROPERTY = "chat4j.codex.oauthRedirectUri";
    private static final String OAUTH_LOGIN_TIMEOUT_SECONDS_PROPERTY = "chat4j.codex.oauthLoginTimeoutSeconds";

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        System.clearProperty(OAUTH_ISSUER_PROPERTY);
        System.clearProperty(OAUTH_CLIENT_ID_PROPERTY);
        System.clearProperty(OAUTH_SCOPES_PROPERTY);
        System.clearProperty(OAUTH_REDIRECT_URI_PROPERTY);
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
    @DisplayName("Begin login creates PKCE authorization URL and triggers browser/clipboard actions")
    void beginLogin_whenConfigured_returnsChallengeAndPromptsActions() throws Exception {
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

        verify(promptActions).copyCodeToClipboard(challenge.authorizationUri());
        verify(promptActions).openBrowser(challenge.authorizationUri());
        verifyNoMoreInteractions(promptActions);
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
                      "access_token": "sk-proj-chat4j-codex-123456789012345678901234567890",
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
                    .isEqualTo("sk-proj-chat4j-codex-123456789012345678901234567890");
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
    @DisplayName("Complete login can be completed immediately through manual submit API")
    void completeLogin_whenManualInputSubmittedDuringWait_completesLogin() throws Exception {
        Path userHome = tempDir.resolve("home");
        AtomicReference<String> tokenRequestBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth/token", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            tokenRequestBody.compareAndSet(null, body);

            byte[] payload = """
                    {
                      "access_token": "sk-proj-chat4j-codex-123456789012345678901234567890",
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
            System.setProperty(OAUTH_LOGIN_TIMEOUT_SECONDS_PROPERTY, "15");
            System.setProperty(OAUTH_REDIRECT_URI_PROPERTY, "http://localhost:1459/auth/callback");

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
                    return null;
                }
            };

            var subject = new CodexAuthResolver(userHome, emptyMap(), HttpClient.newHttpClient(), promptActions);
            CodexAuthResolver.CodexLoginChallenge challenge = subject.beginLogin();

            AtomicReference<CodexAuthResolver.CodexAuthActionResult> resultRef = new AtomicReference<>();
            Thread loginThread = Thread.startVirtualThread(() -> resultRef.set(subject.completeLogin(challenge)));

            boolean accepted = false;
            String redirectInput = "http://localhost:1459/auth/callback?code=manual-code-456&state=%s"
                    .formatted(challenge.state());
            for (int i = 0; i < 30; i++) {
                if (subject.submitManualAuthorizationInput(challenge, redirectInput)) {
                    accepted = true;
                    break;
                }
                Thread.sleep(50);
            }

            loginThread.join();

            assertThat(accepted).isTrue();
            assertThat(resultRef.get()).isNotNull();
            assertThat(resultRef.get().success()).isTrue();
            assertThat(subject.resolveBearerTokenOrNull())
                    .isEqualTo("sk-proj-chat4j-codex-123456789012345678901234567890");
            assertThat(tokenRequestBody.get())
                    .contains("grant_type=authorization_code")
                    .contains("code=manual-code-456");
        } finally {
            server.stop(0);
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
                  "accessToken": "sk-proj-chat4j-codex-123456789012345678901234567890",
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
                  "accessToken": "sk-proj-chat4j-codex-123456789012345678901234567890",
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
