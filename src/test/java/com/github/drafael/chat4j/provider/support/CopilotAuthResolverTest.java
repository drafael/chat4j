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
import java.util.Set;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class CopilotAuthResolverTest {

    private static final String DEVICE_CODE_ENDPOINT_PROPERTY = "chat4j.copilot.deviceCodeEndpoint";
    private static final String ACCESS_TOKEN_ENDPOINT_PROPERTY = "chat4j.copilot.accessTokenEndpoint";
    private static final String OAUTH_CLIENT_ID_PROPERTY = "chat4j.copilot.oauthClientId";
    private static final String OAUTH_SCOPES_PROPERTY = "chat4j.copilot.oauthScopes";

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        System.clearProperty(DEVICE_CODE_ENDPOINT_PROPERTY);
        System.clearProperty(ACCESS_TOKEN_ENDPOINT_PROPERTY);
        System.clearProperty(OAUTH_CLIENT_ID_PROPERTY);
        System.clearProperty(OAUTH_SCOPES_PROPERTY);
    }

    @Test
    @DisplayName("OAuth client detection supports system property configuration")
    void isOAuthClientConfigured_whenSystemPropertyPresent_returnsTrue() {
        Path userHome = tempDir.resolve("home");
        System.setProperty(OAUTH_CLIENT_ID_PROPERTY, "chat4j-client-id");

        var subject = new CopilotAuthResolver(userHome, Map.of(), HttpClient.newHttpClient());

        assertThat(subject.isOAuthClientConfigured()).isTrue();
    }

    @Test
    @DisplayName("Login fails fast when Chat4J OAuth client ID is not configured")
    void login_whenOAuthClientIdMissing_returnsConfigurationFailure() {
        Path userHome = tempDir.resolve("home");
        CopilotAuthResolver.UserPromptActions promptActions = mock(CopilotAuthResolver.UserPromptActions.class);
        var subject = new CopilotAuthResolver(userHome, Map.of(), HttpClient.newHttpClient(), promptActions);

        CopilotAuthResolver.CopilotAuthActionResult result = subject.login();

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("GitHub OAuth client ID is not configured");
        verifyNoInteractions(promptActions);
    }

    @Test
    @DisplayName("Resolver reports unauthorized when no stored Chat4J token exists")
    void resolveStatus_whenNoStoredToken_returnsUnauthorized() {
        Path userHome = tempDir.resolve("home");
        var subject = new CopilotAuthResolver(userHome, Map.of(), HttpClient.newHttpClient());

        var status = subject.resolveStatus();

        assertThat(status.authorized()).isFalse();
        assertThat(status.message()).contains("Not authorized");
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
            byte[] body = "{\"access_token\":\"gho_chat4j_123456789012345678901234567890\"}".getBytes();
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
                    .isEqualTo("gho_chat4j_123456789012345678901234567890");
            assertThat(subject.resolveStatus().authorized()).isTrue();
            assertThat(subject.resolveStatus().source()).isEqualTo("Chat4J OAuth");
            assertThat(deviceRequestBody.get())
                    .contains("client_id=chat4j-client-id")
                    .contains("scope=read%3Auser+user%3Aemail");

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
    @DisplayName("Resolver accepts stored session even when oauthScopes metadata is absent")
    void resolveStatus_whenStoredTokenHasNoOauthScopesMetadata_returnsAuthorized() throws Exception {
        Path userHome = tempDir.resolve("home");
        Path tokenFile = userHome.resolve(".config/chat4j/copilot-auth.json");
        Files.createDirectories(tokenFile.getParent());
        Files.writeString(tokenFile, """
                {
                  "accessToken": "gho_chat4j_123456789012345678901234567890",
                  "updatedAtEpochMs": 1776600000000,
                  "source": "Chat4J OAuth"
                }
                """);

        var subject = new CopilotAuthResolver(userHome, Map.of(), HttpClient.newHttpClient());

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
                  "accessToken": "gho_chat4j_123456789012345678901234567890",
                  "updatedAtEpochMs": 1776600000000,
                  "source": "Chat4J OAuth"
                }
                """);

        var subject = new CopilotAuthResolver(userHome, Map.of(), HttpClient.newHttpClient());

        CopilotAuthResolver.CopilotAuthActionResult result = subject.logout();

        assertThat(result.success()).isTrue();
        assertThat(subject.resolveBearerTokenOrNull()).isNull();
    }
}
