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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class CodexAuthResolverTest {

    private static final String OAUTH_ISSUER_PROPERTY = "chat4j.codex.oauthIssuer";
    private static final String OAUTH_CLIENT_ID_PROPERTY = "chat4j.codex.oauthClientId";
    private static final String OAUTH_SCOPES_PROPERTY = "chat4j.codex.oauthScopes";

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        System.clearProperty(OAUTH_ISSUER_PROPERTY);
        System.clearProperty(OAUTH_CLIENT_ID_PROPERTY);
        System.clearProperty(OAUTH_SCOPES_PROPERTY);
    }

    @Test
    @DisplayName("OAuth client detection supports system property configuration")
    void isOAuthClientConfigured_whenSystemPropertyPresent_returnsTrue() {
        Path userHome = tempDir.resolve("home");
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-codex-client-id");

        var subject = new CodexAuthResolver(userHome, Map.of(), HttpClient.newHttpClient());

        assertThat(subject.isOAuthClientConfigured()).isTrue();
    }

    @Test
    @DisplayName("Login fails fast when Chat4J Codex OAuth client ID is not configured")
    void login_whenOAuthClientIdMissing_returnsConfigurationFailure() {
        Path userHome = tempDir.resolve("home");
        CodexAuthResolver.UserPromptActions promptActions = mock(CodexAuthResolver.UserPromptActions.class);
        var subject = new CodexAuthResolver(userHome, Map.of(), HttpClient.newHttpClient(), promptActions);

        CodexAuthResolver.CodexAuthActionResult result = subject.login();

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("OpenAI Codex OAuth client ID is not configured");
        verifyNoInteractions(promptActions);
    }

    @Test
    @DisplayName("Resolver reports unauthorized when no stored Chat4J token exists")
    void resolveStatus_whenNoStoredToken_returnsUnauthorized() {
        Path userHome = tempDir.resolve("home");
        var subject = new CodexAuthResolver(userHome, Map.of(), HttpClient.newHttpClient());

        var status = subject.resolveStatus();

        assertThat(status.authorized()).isFalse();
        assertThat(status.message()).contains("Not authorized");
    }

    @Test
    @DisplayName("Login flow stores Chat4J Codex token, exchanges oauth code for api key, opens browser, and copies code")
    void login_whenDeviceFlowCompletes_storesTokenAndTriggersPromptActions() throws Exception {
        Path userHome = tempDir.resolve("home");
        AtomicReference<String> deviceRequestBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/accounts/deviceauth/usercode", exchange -> {
            deviceRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {
                      "device_auth_id": "device-auth-id-123",
                      "user_code": "WXYZ-9876",
                      "expires_in": 600,
                      "interval": 1
                    }
                    """.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/accounts/deviceauth/token", exchange -> {
            byte[] body = """
                    {
                      "access_token": "eyJhbGciOiJIUzI1NiJ9.mock-device-token-value-1234567890",
                      "authorization_code": "authorization-code-123",
                      "code_verifier": "verifier-token-123"
                    }
                    """.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/oauth/token", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] payload;
            if (body.contains("grant_type=authorization_code")) {
                payload = """
                        {
                          "id_token": "id-token-123456789012345678901234567890",
                          "access_token": "oauth-access-token-123456789012345678901234",
                          "refresh_token": "refresh-token"
                        }
                        """.getBytes(StandardCharsets.UTF_8);
            } else {
                payload = """
                        {
                          "access_token": "sk-proj-chat4j-codex-123456789012345678901234567890"
                        }
                        """.getBytes(StandardCharsets.UTF_8);
            }

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

            CodexAuthResolver.UserPromptActions promptActions = mock(CodexAuthResolver.UserPromptActions.class);
            var subject = new CodexAuthResolver(
                    userHome,
                    Map.of(),
                    HttpClient.newHttpClient(),
                    promptActions
            );

            CodexAuthResolver.CodexAuthActionResult result = subject.login();

            assertThat(result.success()).isTrue();
            assertThat(subject.resolveBearerTokenOrNull())
                    .isEqualTo("sk-proj-chat4j-codex-123456789012345678901234567890");
            assertThat(subject.resolveStatus().authorized()).isTrue();
            assertThat(subject.resolveStatus().source()).isEqualTo("Chat4J OAuth");
            assertThat(deviceRequestBody.get()).contains("\"client_id\":\"chat4j-codex-client-id\"");

            Path tokenFile = userHome.resolve(".config/chat4j/codex-auth.json");
            if (tokenFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                assertThat(Files.getPosixFilePermissions(tokenFile))
                        .isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            }

            verify(promptActions).copyCodeToClipboard("WXYZ-9876");
            verify(promptActions).openBrowser("http://127.0.0.1:%d/codex/device".formatted(port));
            verifyNoMoreInteractions(promptActions);
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

        var subject = new CodexAuthResolver(userHome, Map.of(), HttpClient.newHttpClient());

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

        var subject = new CodexAuthResolver(userHome, Map.of(), HttpClient.newHttpClient());

        CodexAuthResolver.CodexAuthActionResult result = subject.logout();

        assertThat(result.success()).isTrue();
        assertThat(subject.resolveBearerTokenOrNull()).isNull();
    }
}
