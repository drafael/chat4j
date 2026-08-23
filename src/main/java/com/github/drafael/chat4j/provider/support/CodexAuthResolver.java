package com.github.drafael.chat4j.provider.support;

import static java.util.Collections.emptyMap;

import com.github.drafael.chat4j.http.HttpBody;
import com.github.drafael.chat4j.http.HttpExchangeRequest;
import com.github.drafael.chat4j.http.HttpExchangeResponse;
import com.github.drafael.chat4j.http.HttpTransport;
import com.github.drafael.chat4j.http.JavaNetHttpTransport;
import com.github.drafael.chat4j.http.JavaNetHttpTransport.RedirectPolicy;
import com.github.drafael.chat4j.json.JsonCodec;
import com.github.drafael.chat4j.persistence.SecureFileStore;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

@Slf4j
public class CodexAuthResolver {

    private static final JsonCodec JSON = JsonCodec.standard();
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
    private static final String DEFAULT_CALLBACK_HOST = "localhost";
    private static final int DEFAULT_LOGIN_TIMEOUT_SECONDS = 300;
    private static final Duration MAX_OAUTH_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final String BUILD_PROPERTIES_RESOURCE = "/build.properties";
    private static final String BUILD_PROPERTIES_CLIENT_ID_KEY = "codexOAuthClientId";
    private static final String BUNDLED_CLIENT_ID_RESOURCE = "/oauth/chat4j-codex-client-id.txt";

    private final Path userHome;
    private final Map<String, String> environment;
    private final HttpTransport transport;
    private final AtomicBoolean explicitAuthOperationInProgress = new AtomicBoolean();
    private final ReentrantLock authFileMutationLock = new ReentrantLock();

    public CodexAuthResolver() {
        this(
                Path.of(System.getProperty("user.home")),
                System.getenv(),
                JavaNetHttpTransport.create(Duration.ofSeconds(3), RedirectPolicy.NEVER)
        );
    }

    public CodexAuthResolver(
            @NonNull Path userHome,
            @NonNull Map<String, String> environment,
            @NonNull HttpTransport transport
    ) {
        this.userHome = userHome;
        this.environment = Map.copyOf(environment);
        this.transport = transport;
    }

    public String resolveBearerToken() {
        String token = resolveBearerTokenOrNull();
        if (StringUtils.isBlank(token)) {
            throw new IllegalStateException("Unable to resolve Chat4J Codex authentication token");
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

            CodexAuthApi.StoredToken stored = JSON.read(
                    Files.readAllBytes(tokenFile),
                    CodexAuthApi.StoredToken.class
            );
            String token = normalizeToken(stored.accessToken());
            if (StringUtils.isBlank(token)) {
                return null;
            }

            String oauthScopes = StringUtils.defaultString(stored.oauthScopes());
            long expiresAtEpochMs = stored.expiresAtEpochMs() == null ? 0L : stored.expiresAtEpochMs();
            String refreshToken = StringUtils.trimToNull(stored.refreshToken());
            if (looksLikeJwtToken(token)) {
                CodexAuthApi.JwtClaims payload = decodeJwtPayload(token);
                long jwtExpiresAtEpochMs = payload == null ? 0L : extractJwtExpiryEpochMs(payload);
                if (jwtExpiresAtEpochMs > 0L) {
                    expiresAtEpochMs = expiresAtEpochMs > 0L
                            ? Math.min(expiresAtEpochMs, jwtExpiresAtEpochMs)
                            : jwtExpiresAtEpochMs;
                }
            }

            if (isExpired(expiresAtEpochMs)) {
                if (StringUtils.isBlank(refreshToken)) {
                    return null;
                }
                return refreshAccessToken(refreshToken, oauthScopes);
            }

            String upgradedToken = upgradeJwtTokenIfPossible(
                    token,
                    oauthScopes,
                    refreshToken,
                    expiresAtEpochMs
            );
            return StringUtils.defaultIfBlank(upgradedToken, token);
        } catch (Exception e) {
            return null;
        } finally {
            authFileMutationLock.unlock();
        }
    }

    public CodexAuthStatus resolveStatus() {
        String token = resolveBearerTokenOrNull();
        if (StringUtils.isBlank(token)) {
            return CodexAuthStatus.unauthorized("Not authorized. Click Login to authorize Chat4J.");
        }

        return CodexAuthStatus.authorized("Authorized via %s".formatted(CHAT4J_TOKEN_SOURCE), CHAT4J_TOKEN_SOURCE);
    }

    public CodexAuthActionResult login(
            @NonNull BooleanSupplier cancellationRequested,
            @NonNull AuthorizationInputProvider authorizationInputProvider
    ) {
        AuthOperation operation = tryBeginAuthOperation();
        if (operation == null) {
            return authOperationInProgressResult();
        }

        log.debug("Starting OpenAI Codex OAuth login");
        try (operation) {
            CodexLoginChallenge challenge = beginLogin();
            String input = authorizationInputProvider.request(challenge);
            return completeLoginWithAuthorizationInput(challenge, input, cancellationRequested);
        } catch (TimeoutException e) {
            return CodexAuthActionResult.failure("OpenAI Codex login timed out.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cancelledLoginResult();
        } catch (Exception e) {
            if (isCancellationRequested(cancellationRequested)) {
                return cancelledLoginResult();
            }
            log.warn("OpenAI Codex OAuth login failed ({})", e.getClass().getSimpleName());
            return CodexAuthActionResult.failure("OpenAI Codex login failed.");
        }
    }

    CodexLoginChallenge beginLogin() {
        try {
            String clientId = resolveOAuthClientId();
            String oauthScopes = resolveOAuthScopes();
            PkcePair pkce = generatePkcePair();
            String state = createState();
            String redirectUri = resolveRedirectUri();

            String authorizationUri = createAuthorizationUri(clientId, oauthScopes, pkce.challenge(), state, redirectUri);
            int timeoutSeconds = resolveLoginTimeoutSeconds();

            return new CodexLoginChallenge(
                    pkce.verifier(),
                    state,
                    authorizationUri,
                    redirectUri,
                    resolveCallbackHost(),
                    timeoutSeconds,
                    loginDeadlineNanos(timeoutSeconds),
                    oauthScopes
            );
        } catch (Exception e) {
            throw new IllegalStateException("Unable to start OpenAI Codex login.", e);
        }
    }

    CodexAuthActionResult completeLoginWithAuthorizationInput(
            @NonNull CodexLoginChallenge challenge,
            String inputText,
            BooleanSupplier cancellationRequested
    ) {
        if (isCancellationRequested(cancellationRequested)) {
            return cancelledLoginResult();
        }
        if (isChallengeExpired(challenge)) {
            return timedOutLoginResult();
        }
        try {
            return completeLogin(challenge, parseAuthorizationInput(inputText), cancellationRequested);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cancelledLoginResult();
        } catch (Exception e) {
            if (isCancellationRequested(cancellationRequested)) {
                return cancelledLoginResult();
            }
            if (isChallengeExpired(challenge)) {
                return timedOutLoginResult();
            }
            log.warn("OpenAI Codex OAuth manual completion failed ({})", e.getClass().getSimpleName());
            return CodexAuthActionResult.failure("OpenAI Codex login failed.");
        }
    }

    public CodexAuthActionResult logout() {
        return logout(() -> false);
    }

    public CodexAuthActionResult logout(BooleanSupplier cancellationRequested) {
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
                    return CodexAuthActionResult.success("Chat4J OAuth session was already signed out.");
                }
            } finally {
                authFileMutationLock.unlock();
            }
            log.info("OpenAI Codex OAuth logout completed");
            return CodexAuthActionResult.success("Logged out from Chat4J OAuth session.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cancelledLogoutResult();
        } catch (Exception e) {
            if (isCancellationRequested(cancellationRequested)) {
                return cancelledLogoutResult();
            }
            log.warn("OpenAI Codex OAuth logout failed ({})", e.getClass().getSimpleName());
            return CodexAuthActionResult.failure("Unable to delete the OpenAI Codex login session.");
        }
    }

    public boolean isOAuthClientConfigured() {
        return StringUtils.isNotBlank(configuredOAuthClientId());
    }

    public String oauthClientConfigurationHint() {
        return "Configure Chat4J Codex OAuth client ID via %s system property, %s environment variable, or bundled build.properties key %s"
                .formatted(OAUTH_CLIENT_ID_PROPERTY, OAUTH_CLIENT_ID_ENV, BUILD_PROPERTIES_CLIENT_ID_KEY);
    }

    public CodexCallbackWait startCallbackWait(@NonNull CodexLoginChallenge challenge) {
        CompletableFuture<String> callbackInputFuture = new CompletableFuture<>();
        if (isChallengeExpired(challenge)) {
            callbackInputFuture.complete("");
            return CodexCallbackWait.manualOnly("OpenAI Codex login challenge expired.", callbackInputFuture);
        }
        URI redirectUri;
        try {
            redirectUri = URI.create(challenge.redirectUri());
        } catch (Exception e) {
            String message = "Invalid callback URI. Use manual callback URL paste.";
            log.warn("{} ({})", message, e.getClass().getSimpleName());
            return CodexCallbackWait.manualOnly(message, callbackInputFuture);
        }

        String path = StringUtils.defaultIfBlank(redirectUri.getPath(), "/auth/callback");
        int port = redirectUri.getPort() > 0 ? redirectUri.getPort() : 80;
        String callbackHost = StringUtils.defaultIfBlank(challenge.callbackHost(), DEFAULT_CALLBACK_HOST);

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(callbackHost, port), 0);
        } catch (Exception e) {
            String message = "Local callback unavailable on %s:%d. Use manual callback URL paste."
                    .formatted(callbackHost, port);
            log.warn("{} ({})", message, e.getClass().getSimpleName());
            return CodexCallbackWait.manualOnly(message, callbackInputFuture);
        }

        server.createContext(path, exchange -> {
            try {
                URI requestUri = exchange.getRequestURI();
                Map<String, String> params = parseQueryParameters(StringUtils.defaultString(requestUri.getRawQuery()));
                String state = StringUtils.trimToNull(params.get("state"));
                String code = StringUtils.trimToNull(params.get("code"));

                if (!Strings.CS.equals(state, challenge.state())) {
                    byte[] responseBody = oauthHtml("Missing or invalid state.", false);
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
                callbackInputFuture.complete(callbackInputFromRequest(challenge, requestUri));
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
        String message = "Listening for local callback on %s:%d.".formatted(callbackHost, port);
        log.debug("OpenAI Codex OAuth {}", message);
        return new CodexCallbackWait(true, message, callbackInputFuture, () -> server.stop(0));
    }

    private CodexAuthActionResult completeLogin(
            CodexLoginChallenge challenge,
            AuthorizationCodeInput input,
            BooleanSupplier cancellationRequested
    ) throws Exception {
        if (isCancellationRequested(cancellationRequested)) {
            return cancelledLoginResult();
        }
        if (input == null || StringUtils.isBlank(input.code())) {
            return CodexAuthActionResult.failure("OpenAI Codex login did not receive an authorization code.");
        }

        if (input.callbackUrl() && StringUtils.isBlank(input.state())) {
            return CodexAuthActionResult.failure("OpenAI login failed: callback state is missing");
        }
        if (StringUtils.isNotBlank(input.state()) && !Strings.CS.equals(input.state(), challenge.state())) {
            return CodexAuthActionResult.failure("OpenAI login failed: state mismatch");
        }

        OauthTokenResponse tokenResponse = exchangeAuthorizationCode(
                resolveOAuthClientId(),
                input.code(),
                challenge.codeVerifier(),
                challenge.redirectUri(),
                remainingChallengeRequestTimeout(challenge)
        );
        if (isCancellationRequested(cancellationRequested)) {
            return cancelledLoginResult();
        }
        if (isChallengeExpired(challenge)) {
            return timedOutLoginResult();
        }

        String resolvedToken = selectPreferredToken(resolveOAuthClientId(), tokenResponse, challenge);
        if (isCancellationRequested(cancellationRequested)) {
            return cancelledLoginResult();
        }
        if (isChallengeExpired(challenge)) {
            return timedOutLoginResult();
        }
        if (StringUtils.isBlank(resolvedToken)) {
            return CodexAuthActionResult.failure("OAuth token exchange returned no usable token");
        }

        long expiresAtEpochMs = resolveExpiresAtEpochMs(tokenResponse.expiresInSeconds());
        BooleanSupplier publicationAborted = () -> (cancellationRequested != null
                && cancellationRequested.getAsBoolean())
                || isChallengeExpired(challenge);
        if (!storeChat4jToken(
                resolvedToken,
                challenge.oauthScopes(),
                tokenResponse.refreshToken(),
                expiresAtEpochMs,
                publicationAborted
        )) {
            return isCancellationRequested(cancellationRequested)
                    ? cancelledLoginResult()
                    : timedOutLoginResult();
        }

        log.info("OpenAI Codex OAuth login completed");
        return CodexAuthActionResult.success("Login completed. Authorized using %s.".formatted(CHAT4J_TOKEN_SOURCE));
    }

    private String callbackInputFromRequest(CodexLoginChallenge challenge, URI requestUri) {
        String rawQuery = StringUtils.defaultString(requestUri.getRawQuery());
        String separator = challenge.redirectUri().contains("?") ? "&" : "?";
        return "%s%s%s".formatted(challenge.redirectUri(), separator, rawQuery);
    }

    private byte[] oauthHtml(String message, boolean success) {
        String title = success ? "Authentication complete" : "Authentication failed";
        String color = success ? "#2e7d32" : "#b71c1c";
        String html = """
                <!doctype html>
                <html>
                <head><meta charset="utf-8"><title>%s</title></head>
                <body style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:24px">
                  <h2 style="color:%s">%s</h2>
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
            String redirectUri,
            Duration requestTimeout
    ) throws Exception {
        String formBody = "grant_type=%s&code=%s&redirect_uri=%s&client_id=%s&code_verifier=%s"
                .formatted(
                        urlEncode("authorization_code"),
                        urlEncode(authorizationCode),
                        urlEncode(redirectUri),
                        urlEncode(clientId),
                        urlEncode(codeVerifier)
                );

        HttpExchangeResponse response = sendTokenRequest(formBody, requestTimeout, null);
        if (!response.successful()) {
            throw new IllegalStateException("OAuth token exchange failed with HTTP %d".formatted(response.statusCode()));
        }

        CodexAuthApi.TokenResponse body = JSON.read(response.body(), CodexAuthApi.TokenResponse.class);
        String idToken = StringUtils.trimToNull(body.idToken());
        String accessToken = normalizeToken(body.accessToken());
        String refreshToken = StringUtils.trimToNull(body.refreshToken());
        int expiresInSeconds = parseIntegerValue(body.expiresIn(), 0);

        if (StringUtils.isBlank(idToken) && StringUtils.isBlank(accessToken)) {
            throw new IllegalStateException("OAuth token exchange response was incomplete");
        }

        return new OauthTokenResponse(idToken, accessToken, refreshToken, expiresInSeconds);
    }

    private String selectPreferredToken(
            String clientId,
            OauthTokenResponse tokenResponse,
            CodexLoginChallenge challenge
    ) throws TimeoutException {
        String apiKey = exchangeIdTokenForApiKey(clientId, tokenResponse.idToken(), challenge);
        if (StringUtils.isNotBlank(apiKey)) {
            return apiKey;
        }

        String accessToken = normalizeToken(tokenResponse.accessToken());
        if (StringUtils.isBlank(accessToken)) {
            return null;
        }

        String exchangedAccessToken = exchangeSubjectTokenForApiKey(clientId, accessToken, challenge);
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

            HttpExchangeResponse response = sendTokenRequest(formBody, Duration.ofSeconds(10), null);
            if (!response.successful()) {
                return null;
            }

            CodexAuthApi.TokenResponse body = JSON.read(response.body(), CodexAuthApi.TokenResponse.class);
            String refreshedAccess = normalizeToken(body.accessToken());
            if (StringUtils.isBlank(refreshedAccess)) {
                return null;
            }

            String refreshedRefresh = StringUtils.trimToNull(body.refreshToken());
            int expiresInSeconds = parseIntegerValue(body.expiresIn(), 0);
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String exchangeIdTokenForApiKey(
            String clientId,
            String idToken,
            CodexLoginChallenge challenge
    ) throws TimeoutException {
        return StringUtils.isBlank(idToken)
                ? null
                : exchangeSubjectTokenForApiKey(
                        clientId,
                        idToken,
                        "urn:ietf:params:oauth:token-type:id_token",
                        remainingChallengeRequestTimeout(challenge)
                );
    }

    private String exchangeSubjectTokenForApiKey(String clientId, String subjectToken) {
        String exchanged = exchangeSubjectTokenForApiKey(
                clientId,
                subjectToken,
                "urn:ietf:params:oauth:token-type:access_token",
                MAX_OAUTH_REQUEST_TIMEOUT
        );
        return StringUtils.isNotBlank(exchanged)
                ? exchanged
                : exchangeSubjectTokenForApiKey(
                        clientId,
                        subjectToken,
                        "urn:ietf:params:oauth:token-type:id_token",
                        MAX_OAUTH_REQUEST_TIMEOUT
                );
    }

    private String exchangeSubjectTokenForApiKey(
            String clientId,
            String subjectToken,
            CodexLoginChallenge challenge
    ) throws TimeoutException {
        String exchanged = exchangeSubjectTokenForApiKey(
                clientId,
                subjectToken,
                "urn:ietf:params:oauth:token-type:access_token",
                remainingChallengeRequestTimeout(challenge)
        );
        return StringUtils.isNotBlank(exchanged)
                ? exchanged
                : exchangeSubjectTokenForApiKey(
                        clientId,
                        subjectToken,
                        "urn:ietf:params:oauth:token-type:id_token",
                        remainingChallengeRequestTimeout(challenge)
                );
    }

    private String exchangeSubjectTokenForApiKey(
            String clientId,
            String subjectToken,
            String subjectTokenType,
            Duration requestTimeout
    ) {
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

            HttpExchangeResponse response = sendTokenRequest(formBody, requestTimeout, null);
            if (!response.successful()) {
                return null;
            }

            CodexAuthApi.TokenResponse body = JSON.read(response.body(), CodexAuthApi.TokenResponse.class);
            return normalizeToken(body.accessToken());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
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
        storeChat4jToken(token, oauthScopes, refreshToken, expiresAtEpochMs, null);
    }

    private boolean storeChat4jToken(
            String token,
            String oauthScopes,
            String refreshToken,
            long expiresAtEpochMs,
            BooleanSupplier cancellationRequested
    ) throws Exception {
        Path tokenFile = chat4jAuthFile();

        var payload = new CodexAuthApi.StoredToken(
                token,
                StringUtils.trimToNull(refreshToken),
                expiresAtEpochMs > 0 ? expiresAtEpochMs : null,
                System.currentTimeMillis(),
                CHAT4J_TOKEN_SOURCE,
                StringUtils.defaultString(oauthScopes)
        );
        String content = JSON.writePrettyString(payload);

        if (cancellationRequested == null) {
            authFileMutationLock.lock();
        } else {
            authFileMutationLock.lockInterruptibly();
        }
        try {
            if (isCancellationRequested(cancellationRequested)) {
                return false;
            }
            SecureFileStore.writeStringAtomically(tokenFile, content, "codex-auth");
            return true;
        } finally {
            authFileMutationLock.unlock();
        }
    }

    private HttpExchangeResponse sendTokenRequest(
            String formBody,
            Duration timeout,
            BooleanSupplier cancellationRequested
    ) throws Exception {
        return transport.send(
                new HttpExchangeRequest(
                        "POST",
                        URI.create(oauthTokenEndpoint()),
                        Map.of(
                                "Accept", "application/json",
                                "Content-Type", "application/x-www-form-urlencoded"
                        ),
                        HttpBody.utf8(formBody),
                        timeout,
                        0
                ),
                cancellationRequested
        );
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
        String configuredHost = StringUtils.trimToNull(System.getProperty(OAUTH_CALLBACK_HOST_PROPERTY));
        if (configuredHost != null) {
            return configuredHost;
        }

        try {
            URI redirectUri = URI.create(resolveRedirectUri());
            String redirectHost = StringUtils.trimToNull(redirectUri.getHost());
            if (redirectHost != null) {
                return redirectHost;
            }
        } catch (Exception ignored) {
            // Fall through to default host.
        }

        return DEFAULT_CALLBACK_HOST;
    }

    private Duration remainingChallengeRequestTimeout(CodexLoginChallenge challenge) throws TimeoutException {
        long remainingNanos = challenge.timeoutDeadlineNanos() == Long.MAX_VALUE
                ? MAX_OAUTH_REQUEST_TIMEOUT.toNanos()
                : challenge.timeoutDeadlineNanos() - System.nanoTime();
        if (remainingNanos <= 0L) {
            throw new TimeoutException("OpenAI Codex login timed out");
        }
        return Duration.ofNanos(Math.min(remainingNanos, MAX_OAUTH_REQUEST_TIMEOUT.toNanos()));
    }

    private long loginDeadlineNanos(int timeoutSeconds) {
        try {
            return Math.addExact(
                    System.nanoTime(),
                    TimeUnit.SECONDS.toNanos(Math.max(timeoutSeconds, 1))
            );
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
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
                && !Strings.CI.contains(clientId, "replace");
    }

    private String issuer() {
        return StringUtils.defaultIfBlank(System.getProperty(OAUTH_ISSUER_PROPERTY), DEFAULT_OAUTH_ISSUER).trim();
    }

    private String oauthAuthorizeEndpoint() {
        String configured = StringUtils.trimToNull(System.getProperty(OAUTH_AUTHORIZE_ENDPOINT_PROPERTY));
        return StringUtils.isNotBlank(configured)
            ? configured
            : "%s/oauth/authorize".formatted(issuer());
    }

    private String oauthTokenEndpoint() {
        String configured = StringUtils.trimToNull(System.getProperty(OAUTH_TOKEN_ENDPOINT_PROPERTY));
        return StringUtils.isNotBlank(configured)
            ? configured
            : "%s/oauth/token".formatted(issuer());
    }

    private Path chat4jAuthFile() {
        return xdgConfigHome().resolve("chat4j").resolve(CHAT4J_AUTH_FILENAME);
    }

    private Path xdgConfigHome() {
        String xdgConfigHome = StringUtils.trimToNull(environment.get("XDG_CONFIG_HOME"));
        return StringUtils.isNotBlank(xdgConfigHome)
            ? Path.of(xdgConfigHome)
            : userHome.resolve(".config");
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

    private String upgradeJwtTokenIfPossible(
            String token,
            String oauthScopes,
            String refreshToken,
            long expiresAtEpochMs
    ) {
        if (!looksLikeJwtToken(token)) {
            return token;
        }

        String clientId = configuredOAuthClientId();
        if (StringUtils.isBlank(clientId)) {
            return token;
        }

        String exchangedToken = exchangeSubjectTokenForApiKey(clientId, token);
        if (StringUtils.isBlank(exchangedToken) || Strings.CS.equals(exchangedToken, token)) {
            return token;
        }

        try {
            storeChat4jToken(
                    exchangedToken,
                    StringUtils.defaultIfBlank(oauthScopes, resolveOAuthScopes()),
                    refreshToken,
                    expiresAtEpochMs
            );
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

    private CodexAuthApi.JwtClaims decodeJwtPayload(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }

            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            return JSON.read(decoded, CodexAuthApi.JwtClaims.class);
        } catch (Exception e) {
            return null;
        }
    }

    private long extractJwtExpiryEpochMs(CodexAuthApi.JwtClaims payload) {
        long exp = parseLongValue(payload.exp(), 0L);
        if (exp <= 0) {
            return 0L;
        }

        return exp > Long.MAX_VALUE / 1000L ? Long.MAX_VALUE : exp * 1000L;
    }

    private AuthorizationCodeInput parseAuthorizationInput(String input) {
        String value = StringUtils.trimToNull(input);
        if (value == null) {
            return null;
        }

        boolean urlShapedInput = Strings.CI.startsWith(value, "http:")
                || Strings.CI.startsWith(value, "https:")
                || Strings.CS.contains(value, "://");
        try {
            URI uri = URI.create(value);
            urlShapedInput = uri.isAbsolute()
                    || StringUtils.isNotBlank(uri.getAuthority())
                    || StringUtils.isNotBlank(uri.getRawQuery());
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
                return new AuthorizationCodeInput(code, state, true);
            }
        } catch (Exception ignored) {
            // Not a full URI; continue with fallback parsing.
        }

        if (urlShapedInput) {
            return new AuthorizationCodeInput(null, null, true);
        }

        if (value.contains("code=")) {
            Map<String, String> params = parseQueryParameters(value);
            return new AuthorizationCodeInput(
                    StringUtils.trimToNull(params.get("code")),
                    StringUtils.trimToNull(params.get("state")),
                    true
            );
        }

        if (value.contains("#")) {
            String[] parts = value.split("#", 2);
            return new AuthorizationCodeInput(
                    StringUtils.trimToNull(parts[0]),
                    parts.length > 1 ? StringUtils.trimToNull(parts[1]) : null,
                    false
            );
        }

        return new AuthorizationCodeInput(value, null, false);
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

    private int parseIntegerValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private long parseLongValue(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private AuthOperation tryBeginAuthOperation() {
        return explicitAuthOperationInProgress.compareAndSet(false, true)
                ? new AuthOperation(explicitAuthOperationInProgress)
                : null;
    }

    private static CodexAuthActionResult authOperationInProgressResult() {
        return CodexAuthActionResult.failure("An OpenAI Codex login or logout is already in progress.");
    }

    private static boolean isCancellationRequested(BooleanSupplier cancellationRequested) {
        return Thread.currentThread().isInterrupted()
                || cancellationRequested != null && cancellationRequested.getAsBoolean();
    }

    private static boolean isChallengeExpired(CodexLoginChallenge challenge) {
        long deadlineNanos = challenge.timeoutDeadlineNanos();
        return deadlineNanos != Long.MAX_VALUE && deadlineNanos - System.nanoTime() <= 0L;
    }

    private static CodexAuthActionResult timedOutLoginResult() {
        return CodexAuthActionResult.failure("OpenAI Codex login timed out.");
    }

    private static CodexAuthActionResult cancelledLoginResult() {
        return CodexAuthActionResult.failure("OpenAI Codex login cancelled.");
    }

    private static CodexAuthActionResult cancelledLogoutResult() {
        return CodexAuthActionResult.failure("OpenAI Codex logout cancelled.");
    }

    @FunctionalInterface
    public interface AuthorizationInputProvider {
        String request(CodexLoginChallenge challenge) throws Exception;
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

    private record PkcePair(String verifier, String challenge) {

        @Override
        public String toString() {
            return "PkcePair[verifier=<masked>, challenge=<masked>]";
        }
    }

    private record AuthorizationCodeInput(String code, String state, boolean callbackUrl) {

        @Override
        public String toString() {
            return "AuthorizationCodeInput[code=<masked>, state=<masked>, callbackUrl=%s]".formatted(callbackUrl);
        }
    }

    private record OauthTokenResponse(String idToken, String accessToken, String refreshToken, int expiresInSeconds) {

        @Override
        public String toString() {
            return "OauthTokenResponse[idToken=<masked>, accessToken=<masked>, refreshToken=<masked>, expiresInSeconds=%d]"
                    .formatted(expiresInSeconds);
        }
    }

    public record CodexLoginChallenge(
            String codeVerifier,
            String state,
            String authorizationUri,
            String redirectUri,
            String callbackHost,
            int timeoutSeconds,
            long timeoutDeadlineNanos,
            String oauthScopes
    ) {

        @Override
        public String toString() {
            return "CodexLoginChallenge[codeVerifier=<masked>, state=<masked>, authorizationUri=<masked>, redirectUri=<masked>, callbackHost=%s, timeoutSeconds=%d, oauthScopes=%s]"
                    .formatted(callbackHost, timeoutSeconds, oauthScopes);
        }
    }

    public record CodexCallbackWait(
            boolean listening,
            String message,
            @NonNull CompletableFuture<String> callbackInputFuture,
            @NonNull Runnable closeAction
    ) {

        public CodexCallbackWait {
            Runnable delegate = closeAction;
            var closed = new AtomicBoolean();
            closeAction = () -> {
                if (closed.compareAndSet(false, true)) {
                    delegate.run();
                }
            };
        }

        private static CodexCallbackWait manualOnly(String message, CompletableFuture<String> callbackInputFuture) {
            return new CodexCallbackWait(false, message, callbackInputFuture, () -> {
            });
        }

        public void close() {
            try {
                closeAction.run();
            } catch (Exception ignored) {
                // Best-effort cleanup.
            }
        }
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
    }
}
