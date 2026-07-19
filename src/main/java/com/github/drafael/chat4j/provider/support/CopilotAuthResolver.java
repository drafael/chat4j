package com.github.drafael.chat4j.provider.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.persistence.SecureFileStore;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
    private final AtomicBoolean explicitAuthOperationInProgress = new AtomicBoolean();
    private final ReentrantLock authFileMutationLock = new ReentrantLock();

    public CopilotAuthResolver() {
        this(Path.of(System.getProperty("user.home")), System.getenv(), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build(), new DesktopUserPromptActions());
    }

    public CopilotAuthResolver(
            @NonNull Path userHome,
            @NonNull Map<String, String> environment,
            @NonNull HttpClient httpClient
    ) {
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
        try {
            authFileMutationLock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        try {
            Path tokenFile = chat4jAuthFile();
            if (!Files.isRegularFile(tokenFile)) {
                return null;
            }

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
        } finally {
            authFileMutationLock.unlock();
        }
    }

    public CopilotAuthStatus resolveStatus() {
        String token = resolveBearerTokenOrNull();
        return StringUtils.isBlank(token)
            ? CopilotAuthStatus.unauthorized("Not authorized. Click Login to authorize Chat4J.")
            : CopilotAuthStatus.authorized("Authorized via %s".formatted(CHAT4J_TOKEN_SOURCE), CHAT4J_TOKEN_SOURCE);
    }

    public CopilotAuthActionResult login() {
        return login(
                () -> false,
                action -> {
                    action.run();
                    return true;
                },
                ignored -> {
                }
        );
    }

    public CopilotAuthActionResult login(
            @NonNull BooleanSupplier cancellationRequested,
            @NonNull Predicate<Runnable> promptActionGate,
            @NonNull Consumer<CopilotLoginChallenge> challengeConsumer
    ) {
        AuthOperation operation = tryBeginAuthOperation();
        if (operation == null) {
            return authOperationInProgressResult();
        }

        log.debug("Starting GitHub Copilot OAuth login");
        try (operation) {
            CopilotLoginChallenge challenge = beginLogin(cancellationRequested, promptActionGate);
            challengeConsumer.accept(challenge);
            return completeLogin(challenge, cancellationRequested);
        } catch (Exception e) {
            if (isCancellationRequested(cancellationRequested)) {
                return cancelledLoginResult();
            }
            log.warn("GitHub Copilot OAuth login failed ({})", e.getClass().getSimpleName());
            return CopilotAuthActionResult.failure("GitHub Copilot login failed.");
        }
    }

    CopilotLoginChallenge beginLogin(BooleanSupplier cancellationRequested) {
        return beginLogin(cancellationRequested, action -> {
            action.run();
            return true;
        });
    }

    CopilotLoginChallenge beginLogin(
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
                    deviceCode.timeoutDeadlineNanos(),
                    deviceCode.oauthScopes(),
                    enterpriseDomain
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub Copilot login cancelled.", e);
        } catch (Exception e) {
            if (isCancellationRequested(cancellationRequested)) {
                throw new IllegalStateException("GitHub Copilot login cancelled.", e);
            }
            throw new IllegalStateException("Unable to start GitHub Copilot login.", e);
        }
    }

    CopilotAuthActionResult completeLogin(CopilotLoginChallenge challenge, BooleanSupplier cancellationRequested) {
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
                    challenge.timeoutDeadlineNanos(),
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
                        "GitHub Copilot login timed out before authorization completed."
                );
            }

            CopilotSessionToken sessionToken = exchangeCopilotSessionToken(githubAccessToken, challenge.enterpriseDomain(), true);
            if (isCancellationRequested(cancellationRequested)) {
                return cancelledLoginResult();
            }
            if (sessionToken == null || StringUtils.isBlank(sessionToken.sessionToken())) {
                return CopilotAuthActionResult.failure("GitHub Copilot token exchange failed");
            }

            if (!storeChat4jToken(
                    sessionToken.sessionToken(),
                    deviceCode.oauthScopes(),
                    githubAccessToken,
                    sessionToken.expiresAtEpochMs(),
                    challenge.enterpriseDomain(),
                    cancellationRequested
            )) {
                return cancelledLoginResult();
            }

            log.info("GitHub Copilot OAuth login completed");
            return CopilotAuthActionResult.success("Login completed. Authorized using %s.".formatted(CHAT4J_TOKEN_SOURCE));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cancelledLoginResult();
        } catch (Exception e) {
            if (isCancellationRequested(cancellationRequested)) {
                return cancelledLoginResult();
            }
            log.warn("GitHub Copilot OAuth login completion failed ({})", e.getClass().getSimpleName());
            return CopilotAuthActionResult.failure("GitHub Copilot login failed.");
        }
    }

    public CopilotAuthActionResult logout() {
        return logout(() -> false);
    }

    public CopilotAuthActionResult logout(BooleanSupplier cancellationRequested) {
        AuthOperation operation = tryBeginAuthOperation();
        if (operation == null) {
            return authOperationInProgressResult();
        }

        try (operation) {
            if (isCancellationRequested(cancellationRequested)) {
                return cancelledLogoutResult();
            }
            authFileMutationLock.lockInterruptibly();
            try {
                if (isCancellationRequested(cancellationRequested)) {
                    return cancelledLogoutResult();
                }
                if (!Files.deleteIfExists(chat4jAuthFile())) {
                    return CopilotAuthActionResult.success("Chat4J OAuth session was already signed out.");
                }
            } finally {
                authFileMutationLock.unlock();
            }
            log.info("GitHub Copilot OAuth logout completed");
            return CopilotAuthActionResult.success("Logged out from Chat4J OAuth session.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cancelledLogoutResult();
        } catch (Exception e) {
            if (isCancellationRequested(cancellationRequested)) {
                return cancelledLogoutResult();
            }
            log.warn("GitHub Copilot OAuth logout failed ({})", e.getClass().getSimpleName());
            return CopilotAuthActionResult.failure("Unable to delete the GitHub Copilot login session.");
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
        long responseReceivedAtNanos = System.nanoTime();
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
        if (expiresInSeconds <= 0) {
            throw new IllegalStateException("Device login response had an invalid expiry");
        }

        return new DeviceCodeResponse(
                deviceCode,
                userCode,
                verificationUri,
                Math.max(intervalSeconds, 2),
                expiresInSeconds,
                timeoutDeadlineNanos(responseReceivedAtNanos, expiresInSeconds),
                oauthScopes
        );
    }

    private String pollForAccessToken(
            DeviceCodeResponse deviceCode,
            String enterpriseDomain,
            BooleanSupplier cancellationRequested
    ) throws Exception {
        String clientId = resolveOAuthClientId();
        long timeoutDeadlineNanos = deviceCode.timeoutDeadlineNanos();
        int intervalSeconds = deviceCode.intervalSeconds();

        while (System.nanoTime() < timeoutDeadlineNanos && !isCancellationRequested(cancellationRequested)) {
            long remainingNanos = remainingNanos(timeoutDeadlineNanos);
            if (remainingNanos == 0L) {
                break;
            }

            String formBody = "client_id=%s&device_code=%s&grant_type=%s".formatted(
                    urlEncode(clientId),
                    urlEncode(deviceCode.deviceCode()),
                    urlEncode("urn:ietf:params:oauth:grant-type:device_code")
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(accessTokenEndpoint(enterpriseDomain)))
                    .timeout(Duration.ofNanos(Math.min(remainingNanos, Duration.ofSeconds(10).toNanos())))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "GitHubCopilotChat/0.35.0")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (isCancellationRequested(cancellationRequested) || remainingNanos(timeoutDeadlineNanos) == 0L) {
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
                sleepUntilNextPoll(intervalSeconds, timeoutDeadlineNanos);
                continue;
            }
            if (Strings.CS.equals(error, "slow_down")) {
                intervalSeconds = intervalSeconds > Integer.MAX_VALUE - 2
                        ? Integer.MAX_VALUE
                        : intervalSeconds + 2;
                sleepUntilNextPoll(intervalSeconds, timeoutDeadlineNanos);
                continue;
            }
            if (Strings.CS.equals(error, "access_denied")) {
                throw new IllegalStateException("GitHub login was canceled.");
            }
            if (Strings.CS.equals(error, "expired_token")) {
                break;
            }

            throw new IllegalStateException("GitHub login failed");
        }

        return null;
    }

    private long timeoutDeadlineNanos(long startedAtNanos, int timeoutSeconds) {
        try {
            return Math.addExact(startedAtNanos, TimeUnit.SECONDS.toNanos(timeoutSeconds));
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private long remainingNanos(long timeoutDeadlineNanos) {
        if (timeoutDeadlineNanos == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(timeoutDeadlineNanos - System.nanoTime(), 0L);
    }

    private void sleepUntilNextPoll(int intervalSeconds, long timeoutDeadlineNanos) throws InterruptedException {
        long remainingNanos = remainingNanos(timeoutDeadlineNanos);
        if (remainingNanos == 0L) {
            return;
        }

        long intervalNanos = TimeUnit.SECONDS.toNanos(intervalSeconds);
        TimeUnit.NANOSECONDS.sleep(Math.min(intervalNanos, remainingNanos));
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

            long currentTimeEpochMs = System.currentTimeMillis();
            long providerExpiresAtEpochMs = epochSecondsToMillis(expiresAtSeconds);
            if (providerExpiresAtEpochMs <= currentTimeEpochMs) {
                if (failOnError) {
                    throw new IllegalStateException("Copilot token exchange returned an expired token");
                }
                return null;
            }

            long refreshAtEpochMs = Math.max(
                    currentTimeEpochMs + Duration.ofMinutes(1).toMillis(),
                    providerExpiresAtEpochMs - TOKEN_REFRESH_SKEW.toMillis()
            );
            return new CopilotSessionToken(sessionToken, Math.min(refreshAtEpochMs, providerExpiresAtEpochMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            if (failOnError) {
                throw new IllegalStateException("Copilot token exchange failed", e);
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
        storeChat4jToken(token, oauthScopes, refreshToken, expiresAtEpochMs, enterpriseDomain, null);
    }

    private boolean storeChat4jToken(
            String token,
            String oauthScopes,
            String refreshToken,
            long expiresAtEpochMs,
            String enterpriseDomain,
            BooleanSupplier cancellationRequested
    ) throws Exception {
        Path tokenFile = chat4jAuthFile();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accessToken", token);
        payload.put("refreshToken", StringUtils.trimToNull(refreshToken));
        payload.put("expiresAtEpochMs", expiresAtEpochMs > 0 ? expiresAtEpochMs : null);
        payload.put("enterpriseDomain", StringUtils.trimToNull(enterpriseDomain));
        payload.put("updatedAtEpochMs", System.currentTimeMillis());
        payload.put("source", CHAT4J_TOKEN_SOURCE);
        payload.put("oauthScopes", StringUtils.defaultString(oauthScopes));

        String content = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(payload);

        if (cancellationRequested == null) {
            authFileMutationLock.lock();
        } else {
            authFileMutationLock.lockInterruptibly();
        }
        try {
            if (isCancellationRequested(cancellationRequested)) {
                return false;
            }
            SecureFileStore.writeStringAtomically(tokenFile, content, "copilot-auth");
            return true;
        } finally {
            authFileMutationLock.unlock();
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

    private AuthOperation tryBeginAuthOperation() {
        return explicitAuthOperationInProgress.compareAndSet(false, true)
                ? new AuthOperation(explicitAuthOperationInProgress)
                : null;
    }

    private static CopilotAuthActionResult authOperationInProgressResult() {
        return CopilotAuthActionResult.failure("A GitHub Copilot login or logout is already in progress.");
    }

    private static boolean isCancellationRequested(BooleanSupplier cancellationRequested) {
        return Thread.currentThread().isInterrupted()
                || cancellationRequested != null && cancellationRequested.getAsBoolean();
    }

    private static CopilotAuthActionResult cancelledLoginResult() {
        return CopilotAuthActionResult.failure("GitHub Copilot login cancelled.");
    }

    private static CopilotAuthActionResult cancelledLogoutResult() {
        return CopilotAuthActionResult.failure("GitHub Copilot logout cancelled.");
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

    private long epochSecondsToMillis(long epochSeconds) {
        return epochSeconds > Long.MAX_VALUE / 1000L ? Long.MAX_VALUE : epochSeconds * 1000L;
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

    private static final class AuthOperation implements AutoCloseable {
        private final AtomicBoolean operationInProgress;
        private final AtomicBoolean closed = new AtomicBoolean();

        private AuthOperation(AtomicBoolean operationInProgress) {
            this.operationInProgress = operationInProgress;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                operationInProgress.set(false);
            }
        }
    }

    private record DeviceCodeResponse(
            String deviceCode,
            String userCode,
            String verificationUri,
            int intervalSeconds,
            int expiresInSeconds,
            long timeoutDeadlineNanos,
            String oauthScopes
    ) {

        @Override
        public String toString() {
            return "DeviceCodeResponse[deviceCode=<masked>, userCode=<masked>, verificationUri=<masked>, intervalSeconds=%d, expiresInSeconds=%d, oauthScopes=%s]"
                    .formatted(intervalSeconds, expiresInSeconds, oauthScopes);
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
            long timeoutDeadlineNanos,
            String oauthScopes,
            String enterpriseDomain
    ) {

        @Override
        public String toString() {
            return "CopilotLoginChallenge[deviceCode=<masked>, userCode=<masked>, verificationUri=<masked>, intervalSeconds=%d, expiresInSeconds=%d, oauthScopes=%s, enterpriseDomain=%s]"
                    .formatted(intervalSeconds, expiresInSeconds, oauthScopes, enterpriseDomain);
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
