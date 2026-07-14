package com.github.drafael.chat4j.provider.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.exception.ExceptionUtils;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import static java.util.Collections.emptyMap;

@Slf4j
public class CopilotAuthResolver {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String CHAT4J_AUTH_FILENAME = "copilot-auth.json";
    private static final String CHAT4J_TOKEN_SOURCE = "Chat4J OAuth";

    private static final String DEVICE_CODE_ENDPOINT_PROPERTY = "chat4j.copilot.deviceCodeEndpoint";
    private static final String ACCESS_TOKEN_ENDPOINT_PROPERTY = "chat4j.copilot.accessTokenEndpoint";
    private static final String COPILOT_TOKEN_ENDPOINT_PROPERTY = "chat4j.copilot.tokenEndpoint";
    private static final String COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY = "chat4j.copilot.allowCustomTokenEndpoint";

    private static final String OAUTH_CLIENT_ID_PROPERTY = "chat4j.copilot.oauthClientId";
    private static final String OAUTH_CLIENT_ID_ENV = "CHAT4J_COPILOT_OAUTH_CLIENT_ID";
    private static final String OAUTH_SCOPES_PROPERTY = "chat4j.copilot.oauthScopes";
    private static final String OAUTH_ENTERPRISE_DOMAIN_PROPERTY = "chat4j.copilot.enterpriseDomain";
    private static final String OAUTH_ENTERPRISE_DOMAIN_ENV = "CHAT4J_COPILOT_ENTERPRISE_DOMAIN";

    private static final String DEFAULT_OAUTH_SCOPES = "read:user";

    private static final String BUILD_PROPERTIES_RESOURCE = "/build.properties";
    private static final String BUILD_PROPERTIES_CLIENT_ID_KEY = "copilotOAuthClientId";
    private static final String BUNDLED_CLIENT_ID_RESOURCE = "/oauth/chat4j-copilot-client-id.txt";

    private static final String DEFAULT_GITHUB_DOMAIN = "github.com";
    private static final Duration TOKEN_REFRESH_SKEW = Duration.ofMinutes(5);

    private static final Map<String, String> COPILOT_OAUTH_HEADERS = Map.of(
            "User-Agent", "GitHubCopilotChat/0.35.0",
            "Editor-Version", "vscode/1.107.0",
            "Editor-Plugin-Version", "copilot-chat/0.35.0",
            "Copilot-Integration-Id", "vscode-chat"
    );

    private final Path userHome;
    private final Map<String, String> environment;
    private final HttpClient httpClient;
    private final UserPromptActions userPromptActions;

    public CopilotAuthResolver() {
        this(Path.of(System.getProperty("user.home")), System.getenv(), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build(), new DesktopUserPromptActions());
    }

    CopilotAuthResolver(Path userHome, Map<String, String> environment, HttpClient httpClient) {
        this(userHome, environment, httpClient, new DesktopUserPromptActions());
    }

    CopilotAuthResolver(Path userHome, Map<String, String> environment, HttpClient httpClient, UserPromptActions userPromptActions) {
        this.userHome = userHome;
        this.environment = environment == null ? emptyMap() : Map.copyOf(environment);
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
            if (StringUtils.isBlank(token)) {
                return null;
            }

            String refreshToken = normalizeToken(root.path("refreshToken").asText(""));
            long expiresAtEpochMs = root.path("expiresAtEpochMs").asLong(0L);
            String enterpriseDomain = StringUtils.trimToNull(root.path("enterpriseDomain").asText(""));
            String oauthScopes = root.path("oauthScopes").asText("");

            if (!isCopilotSessionToken(token)) {
                if (!isGitHubOAuthToken(token)) {
                    return null;
                }

                CopilotSessionToken migrated = exchangeCopilotSessionToken(token, enterpriseDomain, false);
                if (migrated == null || StringUtils.isBlank(migrated.sessionToken())) {
                    return null;
                }

                String sourceToken = StringUtils.defaultIfBlank(refreshToken, token);
                storeChat4jToken(
                        migrated.sessionToken(),
                        oauthScopes,
                        sourceToken,
                        migrated.expiresAtEpochMs(),
                        enterpriseDomain
                );
                token = migrated.sessionToken();
                refreshToken = sourceToken;
                expiresAtEpochMs = migrated.expiresAtEpochMs();
            }

            if (isExpired(expiresAtEpochMs)) {
                if (StringUtils.isBlank(refreshToken)) {
                    return null;
                }

                CopilotSessionToken refreshed = exchangeCopilotSessionToken(refreshToken, enterpriseDomain, false);
                if (refreshed == null || StringUtils.isBlank(refreshed.sessionToken())) {
                    return null;
                }

                storeChat4jToken(
                        refreshed.sessionToken(),
                        oauthScopes,
                        refreshToken,
                        refreshed.expiresAtEpochMs(),
                        enterpriseDomain
                );
                token = refreshed.sessionToken();
            }

            return isCopilotSessionToken(token) ? token : null;
        } catch (Exception e) {
            return null;
        }
    }

    public CopilotAuthStatus resolveStatus() {
        String token = resolveBearerTokenOrNull();
        return StringUtils.isBlank(token)
            ? CopilotAuthStatus.unauthorized("Not authorized. Click Login to authorize Chat4J.")
            : CopilotAuthStatus.authorized("Authorized via %s".formatted(CHAT4J_TOKEN_SOURCE), CHAT4J_TOKEN_SOURCE);
    }

    public CopilotAuthActionResult login() {
        log.debug("Starting GitHub Copilot OAuth login");
        try {
            CopilotLoginChallenge challenge = beginLogin();
            return completeLogin(challenge);
        } catch (Exception e) {
            log.warn("GitHub Copilot OAuth login failed: {}", ExceptionUtils.getMessage(e));
            return CopilotAuthActionResult.failure(firstLine(ExceptionUtils.getMessage(e)));
        }
    }

    public CopilotLoginChallenge beginLogin() {
        return beginLogin(() -> false);
    }

    public CopilotLoginChallenge beginLogin(BooleanSupplier cancellationRequested) {
        return beginLogin(cancellationRequested, action -> {
            action.run();
            return true;
        });
    }

    public CopilotLoginChallenge beginLogin(
            BooleanSupplier cancellationRequested,
            Predicate<Runnable> promptActionGate
    ) {
        if (isCancellationRequested(cancellationRequested)) {
            throw new IllegalStateException("GitHub Copilot login cancelled.");
        }

        try {
            String enterpriseDomain = resolveEnterpriseDomain();
            DeviceCodeResponse deviceCode = requestDeviceCode(enterpriseDomain);
            if (isCancellationRequested(cancellationRequested)) {
                throw new IllegalStateException("GitHub Copilot login cancelled.");
            }
            triggerLoginPromptActions(deviceCode, cancellationRequested, promptActionGate);
            return new CopilotLoginChallenge(
                    deviceCode.deviceCode(),
                    deviceCode.userCode(),
                    deviceCode.verificationUri(),
                    deviceCode.intervalSeconds(),
                    deviceCode.expiresInSeconds(),
                    deviceCode.oauthScopes(),
                    enterpriseDomain
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub Copilot login cancelled.", e);
        } catch (Exception e) {
            throw new IllegalStateException(firstLine(e.getMessage()), e);
        }
    }

    public CopilotAuthActionResult completeLogin(CopilotLoginChallenge challenge) {
        return completeLogin(challenge, () -> false);
    }

    public CopilotAuthActionResult completeLogin(CopilotLoginChallenge challenge, BooleanSupplier cancellationRequested) {
        if (challenge == null) {
            return CopilotAuthActionResult.failure("Login challenge is missing");
        }
        if (isCancellationRequested(cancellationRequested)) {
            return cancelledLoginResult();
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

            String githubAccessToken = pollForAccessToken(
                    deviceCode,
                    challenge.enterpriseDomain(),
                    cancellationRequested
            );
            if (isCancellationRequested(cancellationRequested)) {
                return cancelledLoginResult();
            }
            if (StringUtils.isBlank(githubAccessToken)) {
                return CopilotAuthActionResult.failure(
                        "Login timed out. Complete sign in at %s using code %s."
                                .formatted(challenge.verificationUri(), challenge.userCode())
                );
            }

            CopilotSessionToken sessionToken = exchangeCopilotSessionToken(githubAccessToken, challenge.enterpriseDomain(), true);
            if (isCancellationRequested(cancellationRequested)) {
                return cancelledLoginResult();
            }
            if (sessionToken == null || StringUtils.isBlank(sessionToken.sessionToken())) {
                return CopilotAuthActionResult.failure("GitHub Copilot token exchange failed");
            }

            if (isCancellationRequested(cancellationRequested)) {
                return cancelledLoginResult();
            }
            storeChat4jToken(
                    sessionToken.sessionToken(),
                    deviceCode.oauthScopes(),
                    githubAccessToken,
                    sessionToken.expiresAtEpochMs(),
                    challenge.enterpriseDomain()
            );

            log.info("GitHub Copilot OAuth login completed");
            return CopilotAuthActionResult.success("Login completed. Authorized using %s.".formatted(CHAT4J_TOKEN_SOURCE));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cancelledLoginResult();
        } catch (Exception e) {
            if (isCancellationRequested(cancellationRequested)) {
                return cancelledLoginResult();
            }
            log.warn("GitHub Copilot OAuth login completion failed: {}", ExceptionUtils.getMessage(e));
            return CopilotAuthActionResult.failure(firstLine(ExceptionUtils.getMessage(e)));
        }
    }

    public CopilotAuthActionResult logout() {
        return logout(() -> false);
    }

    public CopilotAuthActionResult logout(BooleanSupplier cancellationRequested) {
        try {
            if (isCancellationRequested(cancellationRequested)) {
                return cancelledLoginResult();
            }
            Path tokenFile = chat4jAuthFile();
            if (!Files.exists(tokenFile)) {
                return CopilotAuthActionResult.success("Chat4J OAuth session was already signed out.");
            }

            if (isCancellationRequested(cancellationRequested)) {
                return cancelledLoginResult();
            }
            Files.delete(tokenFile);
            log.info("GitHub Copilot OAuth logout completed");
            return CopilotAuthActionResult.success("Logged out from Chat4J OAuth session.");
        } catch (Exception e) {
            if (isCancellationRequested(cancellationRequested)) {
                return cancelledLoginResult();
            }
            log.warn("GitHub Copilot OAuth logout failed: {}", ExceptionUtils.getMessage(e));
            return CopilotAuthActionResult.failure(firstLine(ExceptionUtils.getMessage(e)));
        }
    }

    public boolean isOAuthClientConfigured() {
        return StringUtils.isNotBlank(configuredOAuthClientId());
    }

    public String oauthClientConfigurationHint() {
        return "Configure Chat4J GitHub OAuth client ID via %s system property, %s environment variable, or bundled build.properties key %s"
                .formatted(OAUTH_CLIENT_ID_PROPERTY, OAUTH_CLIENT_ID_ENV, BUILD_PROPERTIES_CLIENT_ID_KEY);
    }

    private DeviceCodeResponse requestDeviceCode(String enterpriseDomain) throws Exception {
        String clientId = resolveOAuthClientId();
        String oauthScopes = resolveOAuthScopes();
        String formBody = "client_id=%s&scope=%s".formatted(
                urlEncode(clientId),
                urlEncode(oauthScopes)
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(deviceCodeEndpoint(enterpriseDomain)))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "GitHubCopilotChat/0.35.0")
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

    private String pollForAccessToken(
            DeviceCodeResponse deviceCode,
            String enterpriseDomain,
            BooleanSupplier cancellationRequested
    ) throws Exception {
        String clientId = resolveOAuthClientId();
        long deadlineEpochMs = System.currentTimeMillis() + Duration.ofSeconds(deviceCode.expiresInSeconds()).toMillis();
        int intervalSeconds = deviceCode.intervalSeconds();

        while (System.currentTimeMillis() < deadlineEpochMs && !isCancellationRequested(cancellationRequested)) {
            String formBody = "client_id=%s&device_code=%s&grant_type=%s".formatted(
                    urlEncode(clientId),
                    urlEncode(deviceCode.deviceCode()),
                    urlEncode("urn:ietf:params:oauth:grant-type:device_code")
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(accessTokenEndpoint(enterpriseDomain)))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "GitHubCopilotChat/0.35.0")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (isCancellationRequested(cancellationRequested)) {
                return null;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("OAuth token exchange failed with HTTP %d".formatted(response.statusCode()));
            }

            JsonNode root = JSON.readTree(response.body());
            String accessToken = normalizeToken(root.path("access_token").asText(""));
            if (StringUtils.isNotBlank(accessToken)) {
                return accessToken;
            }

            String error = StringUtils.trimToNull(root.path("error").asText(""));
            if (Strings.CS.equals(error, "authorization_pending")) {
                Thread.sleep(Duration.ofSeconds(intervalSeconds));
                continue;
            }
            if (Strings.CS.equals(error, "slow_down")) {
                intervalSeconds = intervalSeconds + 2;
                Thread.sleep(Duration.ofSeconds(intervalSeconds));
                continue;
            }
            if (Strings.CS.equals(error, "access_denied")) {
                throw new IllegalStateException(
                        "GitHub login was canceled. Retry at %s with code %s."
                                .formatted(deviceCode.verificationUri(), deviceCode.userCode())
                );
            }
            if (Strings.CS.equals(error, "expired_token")) {
                break;
            }

            String errorDescription = StringUtils.trimToNull(root.path("error_description").asText(""));
            throw new IllegalStateException(StringUtils.defaultIfBlank(errorDescription, "GitHub login failed"));
        }

        return null;
    }

    private CopilotSessionToken exchangeCopilotSessionToken(
            String githubAccessToken,
            String enterpriseDomain,
            boolean failOnError
    ) {
        if (StringUtils.isBlank(githubAccessToken)) {
            return null;
        }

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(copilotTokenEndpoint(enterpriseDomain)))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer %s".formatted(githubAccessToken));

            COPILOT_OAUTH_HEADERS.forEach(requestBuilder::header);

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (failOnError) {
                    throw new IllegalStateException("Copilot token exchange failed with HTTP %d".formatted(response.statusCode()));
                }
                return null;
            }

            JsonNode root = JSON.readTree(response.body());
            String sessionToken = normalizeToken(root.path("token").asText(""));
            long expiresAtSeconds = root.path("expires_at").asLong(0L);
            if (StringUtils.isBlank(sessionToken) || expiresAtSeconds <= 0L) {
                if (failOnError) {
                    throw new IllegalStateException("Invalid Copilot token exchange response");
                }
                return null;
            }

            long expiresAtEpochMs = Math.max(
                    System.currentTimeMillis() + Duration.ofMinutes(1).toMillis(),
                    expiresAtSeconds * 1000L - TOKEN_REFRESH_SKEW.toMillis()
            );
            return new CopilotSessionToken(sessionToken, expiresAtEpochMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            if (failOnError) {
                throw new IllegalStateException(firstLine(ExceptionUtils.getMessage(e)), e);
            }
            return null;
        }
    }

    private void storeChat4jToken(
            String token,
            String oauthScopes,
            String refreshToken,
            long expiresAtEpochMs,
            String enterpriseDomain
    ) throws Exception {
        Path tokenFile = chat4jAuthFile();
        Path tokenDir = tokenFile.getParent();
        Files.createDirectories(tokenDir);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accessToken", token);
        payload.put("refreshToken", StringUtils.trimToNull(refreshToken));
        payload.put("expiresAtEpochMs", expiresAtEpochMs > 0 ? expiresAtEpochMs : null);
        payload.put("enterpriseDomain", StringUtils.trimToNull(enterpriseDomain));
        payload.put("updatedAtEpochMs", System.currentTimeMillis());
        payload.put("source", CHAT4J_TOKEN_SOURCE);
        payload.put("oauthScopes", StringUtils.defaultString(oauthScopes));

        String content = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(payload);

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

    private void triggerLoginPromptActions(
            DeviceCodeResponse deviceCode,
            BooleanSupplier cancellationRequested,
            Predicate<Runnable> promptActionGate
    ) {
        if (isCancellationRequested(cancellationRequested)) {
            return;
        }
        try {
            promptActionGate.test(() -> copyCodeToClipboard(deviceCode.userCode()));
        } catch (Exception ignored) {
            // Keep login flow running even if clipboard interaction fails.
        }

        if (isCancellationRequested(cancellationRequested)) {
            return;
        }
        try {
            promptActionGate.test(() -> openBrowser(deviceCode.verificationUri()));
        } catch (Exception ignored) {
            // Keep login flow running even if browser interaction fails.
        }
    }

    private void copyCodeToClipboard(String userCode) {
        try {
            userPromptActions.copyCodeToClipboard(userCode);
        } catch (Exception ignored) {
            // Keep login flow running even if clipboard interaction fails.
        }
    }

    private void openBrowser(String verificationUri) {
        try {
            userPromptActions.openBrowser(verificationUri);
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

    private String resolveEnterpriseDomain() {
        String configured = StringUtils.trimToNull(StringUtils.defaultIfBlank(
                System.getProperty(OAUTH_ENTERPRISE_DOMAIN_PROPERTY),
                environment.get(OAUTH_ENTERPRISE_DOMAIN_ENV)
        ));
        if (StringUtils.isBlank(configured)) {
            return null;
        }

        String normalized = normalizeDomain(configured);
        if (normalized == null) {
            throw new IllegalStateException("Invalid GitHub Enterprise domain: %s".formatted(configured));
        }

        if (Strings.CI.equals(normalized, DEFAULT_GITHUB_DOMAIN)) {
            return null;
        }

        return normalized;
    }

    private String normalizeDomain(String input) {
        String trimmed = StringUtils.trimToNull(input);
        if (trimmed == null) {
            return null;
        }

        try {
            URI uri = trimmed.contains("://") ? URI.create(trimmed) : URI.create("https://" + trimmed);
            String host = StringUtils.trimToNull(uri.getHost());
            return host == null ? null : host.toLowerCase();
        } catch (Exception e) {
            return null;
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
                && !Strings.CI.contains(clientId, "replace");
    }

    private String deviceCodeEndpoint(String enterpriseDomain) {
        String configured = StringUtils.trimToNull(System.getProperty(DEVICE_CODE_ENDPOINT_PROPERTY));
        if (configured != null) {
            return configured;
        }

        String domain = StringUtils.defaultIfBlank(enterpriseDomain, DEFAULT_GITHUB_DOMAIN);
        return "https://%s/login/device/code".formatted(domain);
    }

    private String accessTokenEndpoint(String enterpriseDomain) {
        String configured = StringUtils.trimToNull(System.getProperty(ACCESS_TOKEN_ENDPOINT_PROPERTY));
        if (configured != null) {
            return configured;
        }

        String domain = StringUtils.defaultIfBlank(enterpriseDomain, DEFAULT_GITHUB_DOMAIN);
        return "https://%s/login/oauth/access_token".formatted(domain);
    }

    String copilotTokenEndpoint(String enterpriseDomain) {
        String defaultEndpoint = StringUtils.isBlank(enterpriseDomain)
                ? "https://api.github.com/copilot_internal/v2/token"
                : "https://api.%s/copilot_internal/v2/token".formatted(enterpriseDomain);
        String configured = StringUtils.trimToNull(System.getProperty(COPILOT_TOKEN_ENDPOINT_PROPERTY));
        if (configured == null || Strings.CS.equals(configured, defaultEndpoint)) {
            return defaultEndpoint;
        }
        if (Boolean.getBoolean(COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY)) {
            return configured;
        }

        log.warn("Ignoring untrusted Copilot token endpoint override");
        return defaultEndpoint;
    }

    private static boolean isCancellationRequested(BooleanSupplier cancellationRequested) {
        return Thread.currentThread().isInterrupted()
                || cancellationRequested != null && cancellationRequested.getAsBoolean();
    }

    private static CopilotAuthActionResult cancelledLoginResult() {
        return CopilotAuthActionResult.failure("GitHub Copilot login cancelled.");
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

    private boolean isCopilotSessionToken(String token) {
        return Strings.CS.contains(token, "tid=");
    }

    private boolean isGitHubOAuthToken(String token) {
        return Strings.CS.startsWith(token, "gho_")
                || Strings.CS.startsWith(token, "ghu_")
                || Strings.CS.startsWith(token, "github_pat_");
    }

    private boolean isExpired(long expiresAtEpochMs) {
        return expiresAtEpochMs > 0 && System.currentTimeMillis() >= expiresAtEpochMs;
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

        @Override
        public String toString() {
            return "DeviceCodeResponse[deviceCode=<masked>, userCode=<masked>, verificationUri=%s, intervalSeconds=%d, expiresInSeconds=%d, oauthScopes=%s]"
                    .formatted(verificationUri, intervalSeconds, expiresInSeconds, oauthScopes);
        }
    }

    private record CopilotSessionToken(String sessionToken, long expiresAtEpochMs) {

        @Override
        public String toString() {
            return "CopilotSessionToken[sessionToken=<masked>, expiresAtEpochMs=%d]".formatted(expiresAtEpochMs);
        }
    }

    public record CopilotLoginChallenge(
            String deviceCode,
            String userCode,
            String verificationUri,
            int intervalSeconds,
            int expiresInSeconds,
            String oauthScopes,
            String enterpriseDomain
    ) {

        @Override
        public String toString() {
            return "CopilotLoginChallenge[deviceCode=<masked>, userCode=<masked>, verificationUri=%s, intervalSeconds=%d, expiresInSeconds=%d, oauthScopes=%s, enterpriseDomain=%s]"
                    .formatted(verificationUri, intervalSeconds, expiresInSeconds, oauthScopes, enterpriseDomain);
        }
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
    }
}
