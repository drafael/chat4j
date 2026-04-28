package com.github.drafael.chat4j.provider.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import javax.swing.JOptionPane;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static java.util.Collections.emptyMap;

@Slf4j
public class CodexAuthResolver {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String CHAT4J_AUTH_FILENAME = "codex-auth.json";
    private static final String CHAT4J_TOKEN_SOURCE = "Chat4J OAuth";

    private static final String OAUTH_ISSUER_PROPERTY = "chat4j.codex.oauthIssuer";
    private static final String OAUTH_AUTHORIZE_ENDPOINT_PROPERTY = "chat4j.codex.oauthAuthorizeEndpoint";
    private static final String OAUTH_TOKEN_ENDPOINT_PROPERTY = "chat4j.codex.oauthTokenEndpoint";

    private static final String OAUTH_CLIENT_ID_PROPERTY = "chat4j.codex.oauthClientId";
    private static final String OAUTH_CLIENT_ID_ENV = "CHAT4J_CODEX_OAUTH_CLIENT_ID";
    private static final String OAUTH_SCOPES_PROPERTY = "chat4j.codex.oauthScopes";
    private static final String OAUTH_ORIGINATOR_PROPERTY = "chat4j.codex.oauthOriginator";

    private static final String OAUTH_REDIRECT_URI_PROPERTY = "chat4j.codex.oauthRedirectUri";
    private static final String OAUTH_CALLBACK_HOST_PROPERTY = "chat4j.codex.oauthCallbackHost";
    private static final String OAUTH_LOGIN_TIMEOUT_SECONDS_PROPERTY = "chat4j.codex.oauthLoginTimeoutSeconds";

    private static final String DEFAULT_OAUTH_ISSUER = "https://auth.openai.com";
    private static final String DEFAULT_OAUTH_SCOPES = "openid profile email offline_access";
    private static final String DEFAULT_OAUTH_ORIGINATOR = "chat4j_java";

    private static final String DEFAULT_REDIRECT_URI = "http://localhost:1455/auth/callback";
    private static final String DEFAULT_CALLBACK_HOST = "127.0.0.1";
    private static final int DEFAULT_LOGIN_TIMEOUT_SECONDS = 300;

    private static final String BUILD_PROPERTIES_RESOURCE = "/build.properties";
    private static final String BUILD_PROPERTIES_CLIENT_ID_KEY = "codexOAuthClientId";
    private static final String BUNDLED_CLIENT_ID_RESOURCE = "/oauth/chat4j-codex-client-id.txt";

    private final Path userHome;
    private final Map<String, String> environment;
    private final HttpClient httpClient;
    private final UserPromptActions userPromptActions;

    public CodexAuthResolver() {
        this(
                Path.of(System.getProperty("user.home")),
                System.getenv(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                new DesktopUserPromptActions()
        );
    }

    CodexAuthResolver(Path userHome) {
        this(
                userHome,
                System.getenv(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                new DesktopUserPromptActions()
        );
    }

    CodexAuthResolver(Path userHome, Map<String, String> environment, HttpClient httpClient) {
        this(userHome, environment, httpClient, new DesktopUserPromptActions());
    }

    CodexAuthResolver(Path userHome, Map<String, String> environment, HttpClient httpClient, UserPromptActions userPromptActions) {
        this.userHome = userHome;
        this.environment = environment == null ? emptyMap() : Map.copyOf(environment);
        this.httpClient = httpClient;
        this.userPromptActions = userPromptActions == null ? new DesktopUserPromptActions() : userPromptActions;
    }

    public String resolveBearerToken() {
        String token = resolveBearerTokenOrNull();
        if (StringUtils.isBlank(token)) {
            throw new IllegalStateException("Unable to resolve Chat4J Codex authentication token");
        }

        return token;
    }

    public String resolveBearerTokenOrNull() {
        Path tokenFile = chat4jAuthFile();
        if (!Files.isRegularFile(tokenFile)) {
            return null;
        }

        try {
            JsonNode root = JSON.readTree(tokenFile.toFile());
            String token = normalizeToken(root.path("accessToken").asText(""));
            if (StringUtils.isBlank(token)) {
                return null;
            }

            String oauthScopes = root.path("oauthScopes").asText("");
            long expiresAtEpochMs = root.path("expiresAtEpochMs").asLong(0L);
            String refreshToken = StringUtils.trimToNull(root.path("refreshToken").asText(""));

            if (isExpired(expiresAtEpochMs) && StringUtils.isNotBlank(refreshToken)) {
                String refreshed = refreshAccessToken(refreshToken, oauthScopes);
                if (StringUtils.isNotBlank(refreshed)) {
                    token = refreshed;
                }
            }

            String upgradedToken = upgradeJwtTokenIfPossible(token, oauthScopes);
            return StringUtils.defaultIfBlank(upgradedToken, token);
        } catch (Exception e) {
            return null;
        }
    }

    public CodexAuthStatus resolveStatus() {
        String token = resolveBearerTokenOrNull();
        if (StringUtils.isBlank(token)) {
            return CodexAuthStatus.unauthorized("Not authorized. Click Login to authorize Chat4J.");
        }

        return CodexAuthStatus.authorized("Authorized via %s".formatted(CHAT4J_TOKEN_SOURCE), CHAT4J_TOKEN_SOURCE);
    }

    public CodexAuthActionResult login() {
        log.debug("Starting OpenAI Codex OAuth login");
        try {
            CodexLoginChallenge challenge = beginLogin();
            return completeLogin(challenge);
        } catch (Exception e) {
            log.warn("OpenAI Codex OAuth login failed: {}", ExceptionUtils.getMessage(e));
            return CodexAuthActionResult.failure(firstLine(ExceptionUtils.getMessage(e)));
        }
    }

    public CodexLoginChallenge beginLogin() {
        try {
            String clientId = resolveOAuthClientId();
            String oauthScopes = resolveOAuthScopes();
            PkcePair pkce = generatePkcePair();
            String state = createState();
            String redirectUri = resolveRedirectUri();

            String authorizationUri = createAuthorizationUri(clientId, oauthScopes, pkce.challenge(), state, redirectUri);
            triggerLoginPromptActions(authorizationUri);

            return new CodexLoginChallenge(
                    pkce.verifier(),
                    state,
                    authorizationUri,
                    redirectUri,
                    resolveCallbackHost(),
                    resolveLoginTimeoutSeconds(),
                    oauthScopes
            );
        } catch (Exception e) {
            throw new IllegalStateException(firstLine(e.getMessage()), e);
        }
    }

    public CodexAuthActionResult completeLogin(CodexLoginChallenge challenge) {
        if (challenge == null) {
            return CodexAuthActionResult.failure("Login challenge is missing");
        }

        try {
            AuthorizationCodeInput callbackInput = waitForAuthorizationCode(challenge);
            AuthorizationCodeInput input = callbackInput;
            if (input == null || StringUtils.isBlank(input.code())) {
                String manualInput = userPromptActions.promptForAuthorizationInput(
                        "Paste redirect URL below, or complete login in browser:",
                        challenge.authorizationUri()
                );
                input = parseAuthorizationInput(manualInput);
            }

            if (input == null || StringUtils.isBlank(input.code())) {
                return CodexAuthActionResult.failure(
                        "Login timed out. Complete sign in at %s."
                                .formatted(challenge.authorizationUri())
                );
            }

            if (StringUtils.isNotBlank(input.state()) && !StringUtils.equals(input.state(), challenge.state())) {
                return CodexAuthActionResult.failure("OpenAI login failed: state mismatch");
            }

            OauthTokenResponse tokenResponse = exchangeAuthorizationCode(
                    resolveOAuthClientId(),
                    input.code(),
                    challenge.codeVerifier(),
                    challenge.redirectUri()
            );

            String resolvedToken = selectPreferredToken(resolveOAuthClientId(), tokenResponse);
            if (StringUtils.isBlank(resolvedToken)) {
                return CodexAuthActionResult.failure("OAuth token exchange returned no usable token");
            }

            long expiresAtEpochMs = resolveExpiresAtEpochMs(tokenResponse.expiresInSeconds());
            storeChat4jToken(
                    resolvedToken,
                    challenge.oauthScopes(),
                    tokenResponse.refreshToken(),
                    expiresAtEpochMs
            );

            log.info("OpenAI Codex OAuth login completed");
            return CodexAuthActionResult.success("Login completed. Authorized using %s.".formatted(CHAT4J_TOKEN_SOURCE));
        } catch (Exception e) {
            log.warn("OpenAI Codex OAuth login completion failed: {}", ExceptionUtils.getMessage(e));
            return CodexAuthActionResult.failure(firstLine(ExceptionUtils.getMessage(e)));
        }
    }

    public CodexAuthActionResult logout() {
        try {
            Path tokenFile = chat4jAuthFile();
            if (!Files.exists(tokenFile)) {
                return CodexAuthActionResult.success("Chat4J OAuth session was already signed out.");
            }

            Files.delete(tokenFile);
            log.info("OpenAI Codex OAuth logout completed");
            return CodexAuthActionResult.success("Logged out from Chat4J OAuth session.");
        } catch (Exception e) {
            log.warn("OpenAI Codex OAuth logout failed: {}", ExceptionUtils.getMessage(e));
            return CodexAuthActionResult.failure(firstLine(ExceptionUtils.getMessage(e)));
        }
    }

    public boolean isOAuthClientConfigured() {
        return StringUtils.isNotBlank(configuredOAuthClientId());
    }

    public String oauthClientConfigurationHint() {
        return "Configure Chat4J Codex OAuth client ID via %s system property, %s environment variable, or bundled build.properties key %s"
                .formatted(OAUTH_CLIENT_ID_PROPERTY, OAUTH_CLIENT_ID_ENV, BUILD_PROPERTIES_CLIENT_ID_KEY);
    }

    private AuthorizationCodeInput waitForAuthorizationCode(CodexLoginChallenge challenge) {
        URI redirectUri;
        try {
            redirectUri = URI.create(challenge.redirectUri());
        } catch (Exception e) {
            return null;
        }

        String path = StringUtils.defaultIfBlank(redirectUri.getPath(), "/auth/callback");
        int port = redirectUri.getPort() > 0 ? redirectUri.getPort() : 80;
        String callbackHost = StringUtils.defaultIfBlank(challenge.callbackHost(), DEFAULT_CALLBACK_HOST);

        CompletableFuture<AuthorizationCodeInput> future = new CompletableFuture<>();
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(callbackHost, port), 0);
        } catch (Exception e) {
            log.debug("Unable to start local Codex callback server on {}:{}: {}", callbackHost, port, ExceptionUtils.getMessage(e));
            return null;
        }

        server.createContext(path, exchange -> {
            try {
                URI requestUri = exchange.getRequestURI();
                Map<String, String> params = parseQueryParameters(StringUtils.defaultString(requestUri.getRawQuery()));
                String state = StringUtils.trimToNull(params.get("state"));
                String code = StringUtils.trimToNull(params.get("code"));

                if (StringUtils.isNotBlank(state) && !StringUtils.equals(state, challenge.state())) {
                    byte[] responseBody = oauthHtml("State mismatch.", false);
                    exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                    exchange.sendResponseHeaders(400, responseBody.length);
                    exchange.getResponseBody().write(responseBody);
                    return;
                }

                if (StringUtils.isBlank(code)) {
                    byte[] responseBody = oauthHtml("Missing authorization code.", false);
                    exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                    exchange.sendResponseHeaders(400, responseBody.length);
                    exchange.getResponseBody().write(responseBody);
                    return;
                }

                byte[] responseBody = oauthHtml("OpenAI authentication completed. You can close this window.", true);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, responseBody.length);
                exchange.getResponseBody().write(responseBody);
                future.complete(new AuthorizationCodeInput(code, state));
            } catch (Exception e) {
                byte[] responseBody = oauthHtml("Internal error while processing callback.", false);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(500, responseBody.length);
                exchange.getResponseBody().write(responseBody);
            } finally {
                exchange.close();
            }
        });

        server.start();
        try {
            return future.get(challenge.timeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            server.stop(0);
        }
    }

    private byte[] oauthHtml(String message, boolean success) {
        String title = success ? "Authentication complete" : "Authentication failed";
        String color = success ? "#2e7d32" : "#b71c1c";
        String html = """
                <!doctype html>
                <html>
                <head><meta charset=\"utf-8\"><title>%s</title></head>
                <body style=\"font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:24px\">
                  <h2 style=\"color:%s\">%s</h2>
                  <p>%s</p>
                </body>
                </html>
                """.formatted(title, color, title, message);
        return html.getBytes(StandardCharsets.UTF_8);
    }

    private OauthTokenResponse exchangeAuthorizationCode(
            String clientId,
            String authorizationCode,
            String codeVerifier,
            String redirectUri
    ) throws Exception {
        String formBody = "grant_type=%s&code=%s&redirect_uri=%s&client_id=%s&code_verifier=%s"
                .formatted(
                        urlEncode("authorization_code"),
                        urlEncode(authorizationCode),
                        urlEncode(redirectUri),
                        urlEncode(clientId),
                        urlEncode(codeVerifier)
                );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(oauthTokenEndpoint()))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OAuth token exchange failed with HTTP %d".formatted(response.statusCode()));
        }

        JsonNode root = JSON.readTree(response.body());
        String idToken = StringUtils.trimToNull(root.path("id_token").asText(""));
        String accessToken = normalizeToken(root.path("access_token").asText(""));
        String refreshToken = StringUtils.trimToNull(root.path("refresh_token").asText(""));
        int expiresInSeconds = parseIntegerNode(root.path("expires_in"), 0);

        if (StringUtils.isBlank(idToken) && StringUtils.isBlank(accessToken)) {
            throw new IllegalStateException("OAuth token exchange response was incomplete");
        }

        return new OauthTokenResponse(idToken, accessToken, refreshToken, expiresInSeconds);
    }

    private String selectPreferredToken(String clientId, OauthTokenResponse tokenResponse) {
        String apiKey = exchangeIdTokenForApiKey(clientId, tokenResponse.idToken());
        if (StringUtils.isNotBlank(apiKey)) {
            return apiKey;
        }

        String accessToken = normalizeToken(tokenResponse.accessToken());
        if (StringUtils.isBlank(accessToken)) {
            return null;
        }

        String exchangedAccessToken = exchangeSubjectTokenForApiKey(clientId, accessToken);
        return StringUtils.defaultIfBlank(exchangedAccessToken, accessToken);
    }

    private String refreshAccessToken(String refreshToken, String oauthScopes) {
        String clientId = configuredOAuthClientId();
        if (StringUtils.isAnyBlank(clientId, refreshToken)) {
            return null;
        }

        try {
            String formBody = "grant_type=%s&refresh_token=%s&client_id=%s"
                    .formatted(
                            urlEncode("refresh_token"),
                            urlEncode(refreshToken),
                            urlEncode(clientId)
                    );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(oauthTokenEndpoint()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }

            JsonNode root = JSON.readTree(response.body());
            String refreshedAccess = normalizeToken(root.path("access_token").asText(""));
            if (StringUtils.isBlank(refreshedAccess)) {
                return null;
            }

            String refreshedRefresh = StringUtils.trimToNull(root.path("refresh_token").asText(""));
            int expiresInSeconds = parseIntegerNode(root.path("expires_in"), 0);
            long expiresAtEpochMs = resolveExpiresAtEpochMs(expiresInSeconds);

            String resolvedToken = StringUtils.defaultIfBlank(
                    exchangeSubjectTokenForApiKey(clientId, refreshedAccess),
                    refreshedAccess
            );

            storeChat4jToken(
                    resolvedToken,
                    StringUtils.defaultIfBlank(oauthScopes, resolveOAuthScopes()),
                    StringUtils.defaultIfBlank(refreshedRefresh, refreshToken),
                    expiresAtEpochMs
            );

            return resolvedToken;
        } catch (Exception e) {
            return null;
        }
    }

    private String exchangeIdTokenForApiKey(String clientId, String idToken) {
        if (StringUtils.isBlank(idToken)) {
            return null;
        }

        return exchangeSubjectTokenForApiKey(clientId, idToken, "urn:ietf:params:oauth:token-type:id_token");
    }

    private String exchangeSubjectTokenForApiKey(String clientId, String subjectToken) {
        String exchanged = exchangeSubjectTokenForApiKey(
                clientId,
                subjectToken,
                "urn:ietf:params:oauth:token-type:access_token"
        );
        if (StringUtils.isNotBlank(exchanged)) {
            return exchanged;
        }

        return exchangeSubjectTokenForApiKey(clientId, subjectToken, "urn:ietf:params:oauth:token-type:id_token");
    }

    private String exchangeSubjectTokenForApiKey(String clientId, String subjectToken, String subjectTokenType) {
        if (StringUtils.isAnyBlank(clientId, subjectToken, subjectTokenType)) {
            return null;
        }

        try {
            String formBody = "grant_type=%s&client_id=%s&requested_token=%s&subject_token=%s&subject_token_type=%s"
                    .formatted(
                            urlEncode("urn:ietf:params:oauth:grant-type:token-exchange"),
                            urlEncode(clientId),
                            urlEncode("openai-api-key"),
                            urlEncode(subjectToken),
                            urlEncode(subjectTokenType)
                    );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(oauthTokenEndpoint()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }

            JsonNode root = JSON.readTree(response.body());
            return normalizeToken(root.path("access_token").asText(""));
        } catch (Exception e) {
            return null;
        }
    }

    private void storeChat4jToken(
            String token,
            String oauthScopes,
            String refreshToken,
            long expiresAtEpochMs
    ) throws Exception {
        Path tokenFile = chat4jAuthFile();
        Path tokenDir = tokenFile.getParent();
        Files.createDirectories(tokenDir);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accessToken", token);
        payload.put("refreshToken", StringUtils.trimToNull(refreshToken));
        payload.put("expiresAtEpochMs", expiresAtEpochMs > 0 ? expiresAtEpochMs : null);
        payload.put("updatedAtEpochMs", System.currentTimeMillis());
        payload.put("source", CHAT4J_TOKEN_SOURCE);
        payload.put("oauthScopes", StringUtils.defaultString(oauthScopes));

        String content = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(payload);

        Path tempFile = Files.createTempFile(tokenDir, "codex-auth", ".tmp");
        try {
            Files.writeString(tempFile, content, StandardCharsets.UTF_8);
            setOwnerOnlyPermissions(tempFile);
            moveTokenFile(tempFile, tokenFile);
            setOwnerOnlyPermissions(tokenFile);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (Exception e) {
                // Best-effort cleanup for temp file.
            }
        }
    }

    private void moveTokenFile(Path sourceFile, Path targetFile) throws Exception {
        try {
            Files.move(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void setOwnerOnlyPermissions(Path tokenFile) {
        try {
            Set<PosixFilePermission> ownerOnlyPermissions = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(tokenFile, ownerOnlyPermissions);
        } catch (Exception e) {
            // Non-POSIX filesystems (e.g., Windows) do not support this API.
        }
    }

    private void triggerLoginPromptActions(String authorizationUri) {
        try {
            userPromptActions.copyCodeToClipboard(authorizationUri);
        } catch (Exception e) {
            // Keep login flow running even if clipboard interaction fails.
        }

        try {
            userPromptActions.openBrowser(authorizationUri);
        } catch (Exception e) {
            // Keep login flow running even if browser interaction fails.
        }
    }

    private String createAuthorizationUri(
            String clientId,
            String oauthScopes,
            String codeChallenge,
            String state,
            String redirectUri
    ) {
        URI baseUri = URI.create(oauthAuthorizeEndpoint());
        String query = "response_type=code"
                + "&client_id=" + urlEncode(clientId)
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&scope=" + urlEncode(oauthScopes)
                + "&code_challenge=" + urlEncode(codeChallenge)
                + "&code_challenge_method=S256"
                + "&state=" + urlEncode(state)
                + "&id_token_add_organizations=true"
                + "&codex_cli_simplified_flow=true"
                + "&originator=" + urlEncode(resolveOriginator());

        String prefix = baseUri.toString().contains("?") ? "&" : "?";
        return baseUri + prefix + query;
    }

    private PkcePair generatePkcePair() {
        byte[] verifierBytes = new byte[32];
        RANDOM.nextBytes(verifierBytes);

        String verifier = base64UrlNoPadding(verifierBytes);
        byte[] digest;
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            digest = messageDigest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create PKCE challenge", e);
        }

        String challenge = base64UrlNoPadding(digest);
        return new PkcePair(verifier, challenge);
    }

    private String createState() {
        byte[] stateBytes = new byte[16];
        RANDOM.nextBytes(stateBytes);
        return base64UrlNoPadding(stateBytes);
    }

    private String base64UrlNoPadding(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String resolveOAuthClientId() {
        String clientId = configuredOAuthClientId();
        if (StringUtils.isBlank(clientId)) {
            throw new IllegalStateException(
                    "OpenAI Codex OAuth client ID is not configured. %s"
                            .formatted(oauthClientConfigurationHint())
            );
        }

        return clientId;
    }

    private String resolveOAuthScopes() {
        return StringUtils.defaultIfBlank(System.getProperty(OAUTH_SCOPES_PROPERTY), DEFAULT_OAUTH_SCOPES).trim();
    }

    private String resolveOriginator() {
        return StringUtils.defaultIfBlank(System.getProperty(OAUTH_ORIGINATOR_PROPERTY), DEFAULT_OAUTH_ORIGINATOR).trim();
    }

    private String resolveRedirectUri() {
        return StringUtils.defaultIfBlank(System.getProperty(OAUTH_REDIRECT_URI_PROPERTY), DEFAULT_REDIRECT_URI).trim();
    }

    private String resolveCallbackHost() {
        return StringUtils.defaultIfBlank(System.getProperty(OAUTH_CALLBACK_HOST_PROPERTY), DEFAULT_CALLBACK_HOST).trim();
    }

    private int resolveLoginTimeoutSeconds() {
        String configured = StringUtils.trimToNull(System.getProperty(OAUTH_LOGIN_TIMEOUT_SECONDS_PROPERTY));
        if (configured == null) {
            return DEFAULT_LOGIN_TIMEOUT_SECONDS;
        }

        try {
            return Math.max(Integer.parseInt(configured), 1);
        } catch (Exception e) {
            return DEFAULT_LOGIN_TIMEOUT_SECONDS;
        }
    }

    private String configuredOAuthClientId() {
        String explicit = StringUtils.trimToNull(StringUtils.defaultIfBlank(
                System.getProperty(OAUTH_CLIENT_ID_PROPERTY),
                environment.get(OAUTH_CLIENT_ID_ENV)
        ));
        if (isUsableClientId(explicit)) {
            return explicit;
        }

        String buildConfigured = StringUtils.trimToNull(readClientIdFromBuildProperties());
        if (isUsableClientId(buildConfigured)) {
            return buildConfigured;
        }

        String bundled = StringUtils.trimToNull(readBundledClientId());
        return isUsableClientId(bundled) ? bundled : null;
    }

    private String readClientIdFromBuildProperties() {
        try (InputStream input = CodexAuthResolver.class.getResourceAsStream(BUILD_PROPERTIES_RESOURCE)) {
            if (input == null) {
                return null;
            }

            Properties properties = new Properties();
            properties.load(input);
            return StringUtils.trimToNull(properties.getProperty(BUILD_PROPERTIES_CLIENT_ID_KEY));
        } catch (Exception e) {
            return null;
        }
    }

    private String readBundledClientId() {
        try (InputStream input = CodexAuthResolver.class.getResourceAsStream(BUNDLED_CLIENT_ID_RESOURCE)) {
            if (input == null) {
                return null;
            }

            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return StringUtils.trimToNull(content);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isUsableClientId(String clientId) {
        return StringUtils.isNotBlank(clientId)
                && !clientId.startsWith("${")
                && !StringUtils.containsIgnoreCase(clientId, "replace");
    }

    private String issuer() {
        return StringUtils.defaultIfBlank(System.getProperty(OAUTH_ISSUER_PROPERTY), DEFAULT_OAUTH_ISSUER).trim();
    }

    private String oauthAuthorizeEndpoint() {
        String configured = StringUtils.trimToNull(System.getProperty(OAUTH_AUTHORIZE_ENDPOINT_PROPERTY));
        if (StringUtils.isNotBlank(configured)) {
            return configured;
        }

        return "%s/oauth/authorize".formatted(issuer());
    }

    private String oauthTokenEndpoint() {
        String configured = StringUtils.trimToNull(System.getProperty(OAUTH_TOKEN_ENDPOINT_PROPERTY));
        if (StringUtils.isNotBlank(configured)) {
            return configured;
        }

        return "%s/oauth/token".formatted(issuer());
    }

    private Path chat4jAuthFile() {
        return xdgConfigHome().resolve("chat4j").resolve(CHAT4J_AUTH_FILENAME);
    }

    private Path xdgConfigHome() {
        String xdgConfigHome = StringUtils.trimToNull(environment.get("XDG_CONFIG_HOME"));
        if (StringUtils.isNotBlank(xdgConfigHome)) {
            return Path.of(xdgConfigHome);
        }

        return userHome.resolve(".config");
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String normalizeToken(String token) {
        if (StringUtils.isBlank(token)) {
            return null;
        }

        String trimmed = token.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            trimmed = trimmed.substring(7).trim();
        }

        if (trimmed.length() < 20 || trimmed.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }

        return trimmed;
    }

    private boolean isExpired(long expiresAtEpochMs) {
        return expiresAtEpochMs > 0 && System.currentTimeMillis() >= expiresAtEpochMs - Duration.ofMinutes(1).toMillis();
    }

    private long resolveExpiresAtEpochMs(int expiresInSeconds) {
        if (expiresInSeconds <= 0) {
            return 0L;
        }
        return System.currentTimeMillis() + Duration.ofSeconds(expiresInSeconds).toMillis();
    }

    private String upgradeJwtTokenIfPossible(String token, String oauthScopes) {
        if (!looksLikeJwtToken(token)) {
            return token;
        }

        String clientId = configuredOAuthClientId();
        if (StringUtils.isBlank(clientId)) {
            return token;
        }

        String exchangedToken = exchangeSubjectTokenForApiKey(clientId, token);
        if (StringUtils.isBlank(exchangedToken) || StringUtils.equals(exchangedToken, token)) {
            return token;
        }

        try {
            JsonNode payload = decodeJwtPayload(token);
            String refreshToken = null;
            long expiresAtEpochMs = 0L;
            if (payload != null) {
                expiresAtEpochMs = extractJwtExpiryEpochMs(payload);
            }

            storeChat4jToken(
                    exchangedToken,
                    StringUtils.defaultIfBlank(oauthScopes, resolveOAuthScopes()),
                    refreshToken,
                    expiresAtEpochMs
            );
        } catch (Exception e) {
            // Keep using upgraded token in-memory even if persistence fails.
        }

        return exchangedToken;
    }

    private boolean looksLikeJwtToken(String token) {
        if (StringUtils.isBlank(token) || !token.startsWith("eyJ")) {
            return false;
        }

        int firstDot = token.indexOf('.');
        int secondDot = token.indexOf('.', firstDot + 1);
        return firstDot > 0 && secondDot > firstDot;
    }

    private JsonNode decodeJwtPayload(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }

            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            return JSON.readTree(decoded);
        } catch (Exception e) {
            return null;
        }
    }

    private long extractJwtExpiryEpochMs(JsonNode payload) {
        long exp = payload.path("exp").asLong(0L);
        if (exp <= 0) {
            return 0L;
        }

        return exp * 1000L;
    }

    private AuthorizationCodeInput parseAuthorizationInput(String input) {
        String value = StringUtils.trimToNull(input);
        if (value == null) {
            return null;
        }

        try {
            URI uri = URI.create(value);
            String code = null;
            String state = null;

            if (StringUtils.isNotBlank(uri.getRawQuery())) {
                Map<String, String> queryParams = parseQueryParameters(uri.getRawQuery());
                code = StringUtils.trimToNull(queryParams.get("code"));
                state = StringUtils.trimToNull(queryParams.get("state"));
            }

            if (StringUtils.isBlank(code) && StringUtils.isNotBlank(uri.getRawFragment())) {
                Map<String, String> fragmentParams = parseQueryParameters(uri.getRawFragment());
                code = StringUtils.trimToNull(fragmentParams.get("code"));
                state = StringUtils.defaultIfBlank(state, StringUtils.trimToNull(fragmentParams.get("state")));
            }

            if (StringUtils.isNotBlank(code)) {
                return new AuthorizationCodeInput(code, state);
            }
        } catch (Exception e) {
            // Not a full URI; continue with fallback parsing.
        }

        if (value.contains("code=")) {
            Map<String, String> params = parseQueryParameters(value);
            return new AuthorizationCodeInput(
                    StringUtils.trimToNull(params.get("code")),
                    StringUtils.trimToNull(params.get("state"))
            );
        }

        if (value.contains("#")) {
            String[] parts = value.split("#", 2);
            return new AuthorizationCodeInput(
                    StringUtils.trimToNull(parts[0]),
                    parts.length > 1 ? StringUtils.trimToNull(parts[1]) : null
            );
        }

        return new AuthorizationCodeInput(value, null);
    }

    private Map<String, String> parseQueryParameters(String query) {
        if (StringUtils.isBlank(query)) {
            return emptyMap();
        }

        String normalized = query;
        int questionMarkIndex = normalized.indexOf('?');
        if (questionMarkIndex >= 0) {
            normalized = normalized.substring(questionMarkIndex + 1);
        }

        Map<String, String> params = new LinkedHashMap<>();
        String[] pairs = normalized.split("&");
        for (String pair : pairs) {
            if (StringUtils.isBlank(pair)) {
                continue;
            }

            String[] keyValue = pair.split("=", 2);
            String key = urlDecode(keyValue[0]);
            String value = keyValue.length > 1 ? urlDecode(keyValue[1]) : "";
            if (StringUtils.isNotBlank(key)) {
                params.put(key, value);
            }
        }

        return params;
    }

    private String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    private int parseIntegerNode(JsonNode node, int defaultValue) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }

        if (node.isInt() || node.isLong()) {
            return node.asInt(defaultValue);
        }

        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText(""));
            } catch (Exception e) {
                return defaultValue;
            }
        }

        return defaultValue;
    }

    private String firstLine(String text) {
        if (StringUtils.isBlank(text)) {
            return "Unknown error";
        }

        int newline = text.indexOf('\n');
        return newline < 0 ? text.trim() : text.substring(0, newline).trim();
    }

    interface UserPromptActions {
        void openBrowser(String authorizationUri) throws Exception;

        void copyCodeToClipboard(String text) throws Exception;

        String promptForAuthorizationInput(String message, String defaultValue) throws Exception;
    }

    private static final class DesktopUserPromptActions implements UserPromptActions {

        @Override
        public void openBrowser(String authorizationUri) throws Exception {
            if (!Desktop.isDesktopSupported()) {
                return;
            }

            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                return;
            }

            desktop.browse(URI.create(authorizationUri));
        }

        @Override
        public void copyCodeToClipboard(String text) {
            if (GraphicsEnvironment.isHeadless()) {
                return;
            }

            Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new StringSelection(StringUtils.defaultString(text)), null);
        }

        @Override
        public String promptForAuthorizationInput(String message, String defaultValue) {
            if (GraphicsEnvironment.isHeadless()) {
                return null;
            }

            Object response = JOptionPane.showInputDialog(
                    null,
                    message,
                    defaultValue
            );
            return response == null ? null : response.toString();
        }
    }

    private record PkcePair(String verifier, String challenge) {
    }

    private record AuthorizationCodeInput(String code, String state) {
    }

    private record OauthTokenResponse(String idToken, String accessToken, String refreshToken, int expiresInSeconds) {
    }

    public record CodexLoginChallenge(
            String codeVerifier,
            String state,
            String authorizationUri,
            String redirectUri,
            String callbackHost,
            int timeoutSeconds,
            String oauthScopes
    ) {
    }

    public record CodexAuthActionResult(boolean success, String message) {

        public static CodexAuthActionResult success(String message) {
            return new CodexAuthActionResult(true, normalizeMessage(message));
        }

        public static CodexAuthActionResult failure(String message) {
            return new CodexAuthActionResult(false, normalizeMessage(message));
        }

        private static String normalizeMessage(String message) {
            return StringUtils.defaultIfBlank(message, "Unknown error");
        }
    }

    public record CodexAuthStatus(boolean authorized, String message, String source) {

        public static CodexAuthStatus authorized(String message, String source) {
            return new CodexAuthStatus(true, normalizeMessage(message), normalizeSource(source));
        }

        public static CodexAuthStatus unauthorized(String message) {
            return new CodexAuthStatus(false, normalizeMessage(message), null);
        }

        private static String normalizeMessage(String message) {
            return StringUtils.defaultIfBlank(message, "Unavailable");
        }

        private static String normalizeSource(String source) {
            return StringUtils.trimToNull(source);
        }

        @Override
        public String toString() {
            String sourceLabel = source == null ? "n/a" : source;
            return "CodexAuthStatus[authorized=%s, source=%s, message=%s]"
                    .formatted(authorized, sourceLabel, message);
        }
    }
}
