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

public class CopilotAuthResolver {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String CHAT4J_AUTH_FILENAME = "copilot-auth.json";
    private static final String CHAT4J_TOKEN_SOURCE = "Chat4J OAuth";

    private static final String DEVICE_CODE_ENDPOINT_PROPERTY = "chat4j.copilot.deviceCodeEndpoint";
    private static final String ACCESS_TOKEN_ENDPOINT_PROPERTY = "chat4j.copilot.accessTokenEndpoint";
    private static final String OAUTH_CLIENT_ID_PROPERTY = "chat4j.copilot.oauthClientId";
    private static final String OAUTH_CLIENT_ID_ENV = "CHAT4J_COPILOT_OAUTH_CLIENT_ID";
    private static final String OAUTH_SCOPES_PROPERTY = "chat4j.copilot.oauthScopes";
    private static final String DEFAULT_OAUTH_SCOPES = "read:user user:email";

    private static final String BUILD_PROPERTIES_RESOURCE = "/build.properties";
    private static final String BUILD_PROPERTIES_CLIENT_ID_KEY = "copilotOAuthClientId";
    private static final String BUNDLED_CLIENT_ID_RESOURCE = "/oauth/chat4j-copilot-client-id.txt";

    private static final String DEFAULT_DEVICE_CODE_ENDPOINT = "https://github.com/login/device/code";
    private static final String DEFAULT_ACCESS_TOKEN_ENDPOINT = "https://github.com/login/oauth/access_token";

    private final Path userHome;
    private final Map<String, String> environment;
    private final HttpClient httpClient;
    private final UserPromptActions userPromptActions;

    public CopilotAuthResolver() {
        this(Path.of(System.getProperty("user.home")), System.getenv(), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build(), new DesktopUserPromptActions());
    }

    CopilotAuthResolver(Path userHome) {
        this(userHome, System.getenv(), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build(), new DesktopUserPromptActions());
    }

    CopilotAuthResolver(Path userHome, Map<String, String> environment) {
        this(userHome, environment, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build(), new DesktopUserPromptActions());
    }

    CopilotAuthResolver(Path userHome, Map<String, String> environment, HttpClient httpClient) {
        this(userHome, environment, httpClient, new DesktopUserPromptActions());
    }

    CopilotAuthResolver(Path userHome, Map<String, String> environment, HttpClient httpClient, UserPromptActions userPromptActions) {
        this.userHome = userHome;
        this.environment = environment == null ? Map.of() : Map.copyOf(environment);
        this.httpClient = httpClient;
        this.userPromptActions = userPromptActions == null ? new DesktopUserPromptActions() : userPromptActions;
    }

    public String resolveBearerToken() {
        String token = resolveBearerTokenOrNull();
        if (StringUtils.isBlank(token)) {
            throw new IllegalStateException("Unable to resolve Chat4J Copilot authentication token");
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
            return StringUtils.isBlank(token) ? null : token;
        } catch (Exception e) {
            return null;
        }
    }

    public CopilotAuthStatus resolveStatus() {
        String token = resolveBearerTokenOrNull();
        if (StringUtils.isBlank(token)) {
            return CopilotAuthStatus.unauthorized("Not authorized. Click Login to authorize Chat4J.");
        }

        return CopilotAuthStatus.authorized("Authorized via %s".formatted(CHAT4J_TOKEN_SOURCE), CHAT4J_TOKEN_SOURCE);
    }

    public CopilotAuthActionResult login() {
        try {
            CopilotLoginChallenge challenge = beginLogin();
            return completeLogin(challenge);
        } catch (Exception e) {
            return CopilotAuthActionResult.failure(firstLine(e.getMessage()));
        }
    }

    public CopilotLoginChallenge beginLogin() {
        try {
            DeviceCodeResponse deviceCode = requestDeviceCode();
            triggerLoginPromptActions(deviceCode);
            return new CopilotLoginChallenge(
                    deviceCode.deviceCode(),
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

    public CopilotAuthActionResult completeLogin(CopilotLoginChallenge challenge) {
        if (challenge == null) {
            return CopilotAuthActionResult.failure("Login challenge is missing");
        }

        try {
            DeviceCodeResponse deviceCode = new DeviceCodeResponse(
                    challenge.deviceCode(),
                    challenge.userCode(),
                    challenge.verificationUri(),
                    challenge.intervalSeconds(),
                    challenge.expiresInSeconds(),
                    challenge.oauthScopes()
            );

            String accessToken = pollForAccessToken(deviceCode);
            if (StringUtils.isBlank(accessToken)) {
                return CopilotAuthActionResult.failure(
                        "Login timed out. Complete sign in at %s using code %s."
                                .formatted(challenge.verificationUri(), challenge.userCode())
                );
            }

            storeChat4jToken(accessToken, deviceCode.oauthScopes());
            return CopilotAuthActionResult.success("Login completed. Authorized using %s.".formatted(CHAT4J_TOKEN_SOURCE));
        } catch (Exception e) {
            return CopilotAuthActionResult.failure(firstLine(e.getMessage()));
        }
    }

    public CopilotAuthActionResult logout() {
        try {
            Path tokenFile = chat4jAuthFile();
            if (!Files.exists(tokenFile)) {
                return CopilotAuthActionResult.success("Chat4J OAuth session was already signed out.");
            }

            Files.delete(tokenFile);
            return CopilotAuthActionResult.success("Logged out from Chat4J OAuth session.");
        } catch (Exception e) {
            return CopilotAuthActionResult.failure(firstLine(e.getMessage()));
        }
    }

    public boolean isOAuthClientConfigured() {
        return StringUtils.isNotBlank(configuredOAuthClientId());
    }

    public String oauthClientConfigurationHint() {
        return "Configure Chat4J GitHub OAuth client ID via %s system property, %s environment variable, or bundled build.properties key %s"
                .formatted(OAUTH_CLIENT_ID_PROPERTY, OAUTH_CLIENT_ID_ENV, BUILD_PROPERTIES_CLIENT_ID_KEY);
    }

    private DeviceCodeResponse requestDeviceCode() throws Exception {
        String clientId = resolveOAuthClientId();
        String oauthScopes = resolveOAuthScopes();
        String formBody = "client_id=%s&scope=%s".formatted(
                urlEncode(clientId),
                urlEncode(oauthScopes)
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(deviceCodeEndpoint()))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Device login request failed with HTTP %d".formatted(response.statusCode()));
        }

        JsonNode root = JSON.readTree(response.body());
        String deviceCode = StringUtils.trimToNull(root.path("device_code").asText(""));
        String userCode = StringUtils.trimToNull(root.path("user_code").asText(""));
        String verificationUri = StringUtils.trimToNull(root.path("verification_uri").asText(""));
        int intervalSeconds = root.path("interval").asInt(5);
        int expiresInSeconds = root.path("expires_in").asInt((int) Duration.ofMinutes(15).toSeconds());

        if (StringUtils.isAnyBlank(deviceCode, userCode, verificationUri)) {
            throw new IllegalStateException("Device login response was incomplete");
        }

        return new DeviceCodeResponse(
                deviceCode,
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
            String formBody = "client_id=%s&device_code=%s&grant_type=%s".formatted(
                    urlEncode(clientId),
                    urlEncode(deviceCode.deviceCode()),
                    urlEncode("urn:ietf:params:oauth:grant-type:device_code")
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(accessTokenEndpoint()))
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
            String accessToken = normalizeToken(root.path("access_token").asText(""));
            if (StringUtils.isNotBlank(accessToken)) {
                return accessToken;
            }

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
                        "GitHub login was canceled. Retry at %s with code %s."
                                .formatted(deviceCode.verificationUri(), deviceCode.userCode())
                );
            }
            if (StringUtils.equals(error, "expired_token")) {
                break;
            }

            String errorDescription = StringUtils.trimToNull(root.path("error_description").asText(""));
            throw new IllegalStateException(StringUtils.defaultIfBlank(errorDescription, "GitHub login failed"));
        }

        return null;
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

        Path tempFile = Files.createTempFile(tokenDir, "copilot-auth", ".tmp");
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
                    "GitHub OAuth client ID is not configured. %s"
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
        try (InputStream input = CopilotAuthResolver.class.getResourceAsStream(BUILD_PROPERTIES_RESOURCE)) {
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
        try (InputStream input = CopilotAuthResolver.class.getResourceAsStream(BUNDLED_CLIENT_ID_RESOURCE)) {
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

    private String deviceCodeEndpoint() {
        return StringUtils.defaultIfBlank(System.getProperty(DEVICE_CODE_ENDPOINT_PROPERTY), DEFAULT_DEVICE_CODE_ENDPOINT);
    }

    private String accessTokenEndpoint() {
        return StringUtils.defaultIfBlank(System.getProperty(ACCESS_TOKEN_ENDPOINT_PROPERTY), DEFAULT_ACCESS_TOKEN_ENDPOINT);
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
            String deviceCode,
            String userCode,
            String verificationUri,
            int intervalSeconds,
            int expiresInSeconds,
            String oauthScopes
    ) {
    }

    public record CopilotLoginChallenge(
            String deviceCode,
            String userCode,
            String verificationUri,
            int intervalSeconds,
            int expiresInSeconds,
            String oauthScopes
    ) {
    }

    public record CopilotAuthActionResult(boolean success, String message) {

        public static CopilotAuthActionResult success(String message) {
            return new CopilotAuthActionResult(true, normalizeMessage(message));
        }

        public static CopilotAuthActionResult failure(String message) {
            return new CopilotAuthActionResult(false, normalizeMessage(message));
        }

        private static String normalizeMessage(String message) {
            return StringUtils.defaultIfBlank(message, "Unknown error");
        }
    }

    public record CopilotAuthStatus(boolean authorized, String message, String source) {

        public static CopilotAuthStatus authorized(String message, String source) {
            return new CopilotAuthStatus(true, normalizeMessage(message), normalizeSource(source));
        }

        public static CopilotAuthStatus unauthorized(String message) {
            return new CopilotAuthStatus(false, normalizeMessage(message), null);
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
            return "CopilotAuthStatus[authorized=%s, source=%s, message=%s]"
                    .formatted(authorized, sourceLabel, message);
        }
    }
}
