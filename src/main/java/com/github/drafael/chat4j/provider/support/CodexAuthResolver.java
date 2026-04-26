package com.github.drafael.chat4j.provider.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.InputStream;
import java.net.URI;
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
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class CodexAuthResolver {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String CHAT4J_AUTH_FILENAME = "codex-auth.json";
    private static final String CHAT4J_TOKEN_SOURCE = "Chat4J OAuth";

    private static final String OAUTH_ISSUER_PROPERTY = "chat4j.codex.oauthIssuer";
    private static final String DEVICE_USER_CODE_ENDPOINT_PROPERTY = "chat4j.codex.deviceUserCodeEndpoint";
    private static final String DEVICE_TOKEN_ENDPOINT_PROPERTY = "chat4j.codex.deviceTokenEndpoint";
    private static final String OAUTH_TOKEN_ENDPOINT_PROPERTY = "chat4j.codex.oauthTokenEndpoint";

    private static final String OAUTH_CLIENT_ID_PROPERTY = "chat4j.codex.oauthClientId";
    private static final String OAUTH_CLIENT_ID_ENV = "CHAT4J_CODEX_OAUTH_CLIENT_ID";
    private static final String OAUTH_SCOPES_PROPERTY = "chat4j.codex.oauthScopes";

    private static final String DEFAULT_OAUTH_ISSUER = "https://auth.openai.com";
    private static final String DEFAULT_OAUTH_SCOPES = "openid profile email offline_access api.connectors.read api.connectors.invoke";

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
        this.environment = environment == null ? Map.of() : Map.copyOf(environment);
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

            String upgradedToken = upgradeJwtTokenIfPossible(token, root.path("oauthScopes").asText(""));
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
        try {
            CodexLoginChallenge challenge = beginLogin();
            return completeLogin(challenge);
        } catch (Exception e) {
            return CodexAuthActionResult.failure(firstLine(e.getMessage()));
        }
    }

    public CodexLoginChallenge beginLogin() {
        try {
            DeviceCodeResponse deviceCode = requestDeviceCode();
            triggerLoginPromptActions(deviceCode);
            return new CodexLoginChallenge(
                    deviceCode.deviceAuthId(),
                    deviceCode.userCode(),
                    deviceCode.verificationUri(),
                    deviceCode.intervalSeconds(),
                    deviceCode.expiresInSeconds(),
                    deviceCode.oauthScopes()
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
            DeviceCodeResponse deviceCode = new DeviceCodeResponse(
                    challenge.deviceAuthId(),
                    challenge.userCode(),
                    challenge.verificationUri(),
                    challenge.intervalSeconds(),
                    challenge.expiresInSeconds(),
                    challenge.oauthScopes()
            );

            String accessToken = pollForAccessToken(deviceCode);
            if (StringUtils.isBlank(accessToken)) {
                return CodexAuthActionResult.failure(
                        "Login timed out. Complete sign in at %s using code %s."
                                .formatted(challenge.verificationUri(), challenge.userCode())
                );
            }

            storeChat4jToken(accessToken, deviceCode.oauthScopes());
            return CodexAuthActionResult.success("Login completed. Authorized using %s.".formatted(CHAT4J_TOKEN_SOURCE));
        } catch (Exception e) {
            return CodexAuthActionResult.failure(firstLine(e.getMessage()));
        }
    }

    public CodexAuthActionResult logout() {
        try {
            Path tokenFile = chat4jAuthFile();
            if (!Files.exists(tokenFile)) {
                return CodexAuthActionResult.success("Chat4J OAuth session was already signed out.");
            }

            Files.delete(tokenFile);
            return CodexAuthActionResult.success("Logged out from Chat4J OAuth session.");
        } catch (Exception e) {
            return CodexAuthActionResult.failure(firstLine(e.getMessage()));
        }
    }

    public boolean isOAuthClientConfigured() {
        return StringUtils.isNotBlank(configuredOAuthClientId());
    }

    public String oauthClientConfigurationHint() {
        return "Configure Chat4J Codex OAuth client ID via %s system property, %s environment variable, or bundled build.properties key %s"
                .formatted(OAUTH_CLIENT_ID_PROPERTY, OAUTH_CLIENT_ID_ENV, BUILD_PROPERTIES_CLIENT_ID_KEY);
    }

    private DeviceCodeResponse requestDeviceCode() throws Exception {
        String clientId = resolveOAuthClientId();
        String oauthScopes = resolveOAuthScopes();

        String requestBody = JSON.writeValueAsString(Map.of("client_id", clientId));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(deviceUserCodeEndpoint()))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Device login request failed with HTTP %d".formatted(response.statusCode()));
        }

        JsonNode root = JSON.readTree(response.body());
        String deviceAuthId = StringUtils.trimToNull(root.path("device_auth_id").asText(""));
        String userCode = StringUtils.trimToNull(root.path("user_code").asText(""));
        int intervalSeconds = parseIntegerNode(root.path("interval"), 5);
        int expiresInSeconds = parseIntegerNode(root.path("expires_in"), (int) Duration.ofMinutes(15).toSeconds());

        String verificationUri = StringUtils.trimToNull(root.path("verification_uri").asText(""));
        if (StringUtils.isBlank(verificationUri)) {
            verificationUri = StringUtils.trimToNull(root.path("verification_url").asText(""));
        }
        if (StringUtils.isBlank(verificationUri)) {
            verificationUri = "%s/codex/device".formatted(issuer());
        }

        if (StringUtils.isAnyBlank(deviceAuthId, userCode, verificationUri)) {
            throw new IllegalStateException("Device login response was incomplete");
        }

        return new DeviceCodeResponse(
                deviceAuthId,
                userCode,
                verificationUri,
                Math.max(intervalSeconds, 2),
                Math.max(expiresInSeconds, 60),
                oauthScopes
        );
    }

    private String pollForAccessToken(DeviceCodeResponse deviceCode) throws Exception {
        String clientId = resolveOAuthClientId();
        long deadlineEpochMs = System.currentTimeMillis() + Duration.ofSeconds(deviceCode.expiresInSeconds()).toMillis();
        int intervalSeconds = deviceCode.intervalSeconds();

        while (System.currentTimeMillis() < deadlineEpochMs) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(deviceTokenEndpoint()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            JSON.writeValueAsString(Map.of(
                                    "device_auth_id", deviceCode.deviceAuthId(),
                                    "user_code", deviceCode.userCode()
                            )),
                            StandardCharsets.UTF_8
                    ))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode root = JSON.readTree(response.body());

                String authorizationCode = StringUtils.trimToNull(root.path("authorization_code").asText(""));
                String codeVerifier = StringUtils.trimToNull(root.path("code_verifier").asText(""));
                if (StringUtils.isNoneBlank(authorizationCode, codeVerifier)) {
                    OauthTokenExchangeResponse oauthTokens = exchangeCodeForTokens(
                            clientId,
                            authorizationCode,
                            codeVerifier
                    );
                    String apiKeyToken = exchangeIdTokenForApiKey(clientId, oauthTokens.idToken());
                    String resolvedToken = normalizeToken(StringUtils.defaultIfBlank(apiKeyToken, oauthTokens.accessToken()));
                    if (StringUtils.isBlank(resolvedToken)) {
                        throw new IllegalStateException("OAuth token exchange returned no usable token");
                    }

                    return resolvedToken;
                }

                String directAccessToken = normalizeToken(root.path("access_token").asText(""));
                if (StringUtils.isNotBlank(directAccessToken)) {
                    String exchangedApiKey = exchangeSubjectTokenForApiKey(clientId, directAccessToken);
                    return StringUtils.defaultIfBlank(exchangedApiKey, directAccessToken);
                }

                throw new IllegalStateException("Device token response was incomplete");
            }

            if (response.statusCode() == 403 || response.statusCode() == 404) {
                Thread.sleep(Duration.ofSeconds(intervalSeconds));
                continue;
            }

            JsonNode root = JSON.readTree(StringUtils.defaultIfBlank(response.body(), "{}"));
            String error = StringUtils.trimToNull(root.path("error").asText(""));
            if (StringUtils.equals(error, "authorization_pending")) {
                Thread.sleep(Duration.ofSeconds(intervalSeconds));
                continue;
            }
            if (StringUtils.equals(error, "slow_down")) {
                intervalSeconds = intervalSeconds + 2;
                Thread.sleep(Duration.ofSeconds(intervalSeconds));
                continue;
            }
            if (StringUtils.equals(error, "access_denied")) {
                throw new IllegalStateException(
                        "OpenAI login was canceled. Retry at %s with code %s."
                                .formatted(deviceCode.verificationUri(), deviceCode.userCode())
                );
            }
            if (StringUtils.equals(error, "expired_token")) {
                break;
            }

            String errorDescription = StringUtils.trimToNull(root.path("error_description").asText(""));
            String message = StringUtils.defaultIfBlank(
                    errorDescription,
                    "Device token polling failed with HTTP %d".formatted(response.statusCode())
            );
            throw new IllegalStateException(message);
        }

        return null;
    }

    private OauthTokenExchangeResponse exchangeCodeForTokens(String clientId, String authorizationCode, String codeVerifier) throws Exception {
        String redirectUri = "%s/deviceauth/callback".formatted(issuer());
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
        if (StringUtils.isBlank(idToken) && StringUtils.isBlank(accessToken)) {
            throw new IllegalStateException("OAuth token exchange response was incomplete");
        }

        return new OauthTokenExchangeResponse(idToken, accessToken);
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
            } catch (Exception ignored) {
                return defaultValue;
            }
        }

        return defaultValue;
    }

    private void storeChat4jToken(String token, String oauthScopes) throws Exception {
        Path tokenFile = chat4jAuthFile();
        Path tokenDir = tokenFile.getParent();
        Files.createDirectories(tokenDir);

        String content = """
                {
                  "accessToken": "%s",
                  "updatedAtEpochMs": %d,
                  "source": "%s",
                  "oauthScopes": "%s"
                }
                """.formatted(token, System.currentTimeMillis(), CHAT4J_TOKEN_SOURCE, StringUtils.defaultString(oauthScopes));

        Path tempFile = Files.createTempFile(tokenDir, "codex-auth", ".tmp");
        try {
            Files.writeString(tempFile, content, StandardCharsets.UTF_8);
            setOwnerOnlyPermissions(tempFile);
            moveTokenFile(tempFile, tokenFile);
            setOwnerOnlyPermissions(tokenFile);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (Exception ignored) {
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
        } catch (Exception ignored) {
            // Non-POSIX filesystems (e.g., Windows) do not support this API.
        }
    }

    private void triggerLoginPromptActions(DeviceCodeResponse deviceCode) {
        try {
            userPromptActions.copyCodeToClipboard(deviceCode.userCode());
        } catch (Exception ignored) {
            // Keep login flow running even if clipboard interaction fails.
        }

        try {
            userPromptActions.openBrowser(deviceCode.verificationUri());
        } catch (Exception ignored) {
            // Keep login flow running even if browser interaction fails.
        }
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

    private String configuredOAuthClientId() {
        String explicit = StringUtils.trimToNull(StringUtils.defaultIfBlank(
                System.getProperty(OAUTH_CLIENT_ID_PROPERTY),
                environment.get(OAUTH_CLIENT_ID_ENV)
        ));
        if (StringUtils.isNotBlank(explicit)) {
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

    private String deviceUserCodeEndpoint() {
        String configured = StringUtils.trimToNull(System.getProperty(DEVICE_USER_CODE_ENDPOINT_PROPERTY));
        if (StringUtils.isNotBlank(configured)) {
            return configured;
        }

        return "%s/api/accounts/deviceauth/usercode".formatted(issuer());
    }

    private String deviceTokenEndpoint() {
        String configured = StringUtils.trimToNull(System.getProperty(DEVICE_TOKEN_ENDPOINT_PROPERTY));
        if (StringUtils.isNotBlank(configured)) {
            return configured;
        }

        return "%s/api/accounts/deviceauth/token".formatted(issuer());
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
            storeChat4jToken(exchangedToken, StringUtils.defaultIfBlank(oauthScopes, resolveOAuthScopes()));
        } catch (Exception ignored) {
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

    private String firstLine(String text) {
        if (StringUtils.isBlank(text)) {
            return "Unknown error";
        }

        int newline = text.indexOf('\n');
        return newline < 0 ? text.trim() : text.substring(0, newline).trim();
    }

    interface UserPromptActions {
        void openBrowser(String verificationUri) throws Exception;

        void copyCodeToClipboard(String userCode) throws Exception;
    }

    private static final class DesktopUserPromptActions implements UserPromptActions {

        @Override
        public void openBrowser(String verificationUri) throws Exception {
            if (!Desktop.isDesktopSupported()) {
                return;
            }

            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                return;
            }

            desktop.browse(URI.create(verificationUri));
        }

        @Override
        public void copyCodeToClipboard(String userCode) {
            if (GraphicsEnvironment.isHeadless()) {
                return;
            }

            Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new StringSelection(userCode), null);
        }
    }

    private record DeviceCodeResponse(
            String deviceAuthId,
            String userCode,
            String verificationUri,
            int intervalSeconds,
            int expiresInSeconds,
            String oauthScopes
    ) {
    }

    private record OauthTokenExchangeResponse(String idToken, String accessToken) {
    }

    public record CodexLoginChallenge(
            String deviceAuthId,
            String userCode,
            String verificationUri,
            int intervalSeconds,
            int expiresInSeconds,
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
