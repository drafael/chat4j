package com.github.drafael.chat4j.provider.capability.models.impl;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.CopilotModelMetadataStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiModelCatalogClientTest {

    private static final String COPILOT_TOKEN_ENDPOINT_PROPERTY = "chat4j.copilot.tokenEndpoint";
    private static final String COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY = "chat4j.copilot.allowCustomTokenEndpoint";

    @TempDir
    private Path tempDir;

    private String originalCopilotTokenEndpoint;
    private String originalCopilotAllowCustomTokenEndpoint;
    private OpenAiModelCatalogClient subject;

    @BeforeEach
    void setUp() {
        originalCopilotTokenEndpoint = System.getProperty(COPILOT_TOKEN_ENDPOINT_PROPERTY);
        originalCopilotAllowCustomTokenEndpoint = System.getProperty(COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY);
        subject = new OpenAiModelCatalogClient(new CopilotModelMetadataStore(tempDir.resolve("default-metadata")));
    }

    @AfterEach
    void tearDown() {
        if (originalCopilotTokenEndpoint == null) {
            System.clearProperty(COPILOT_TOKEN_ENDPOINT_PROPERTY);
        } else {
            System.setProperty(COPILOT_TOKEN_ENDPOINT_PROPERTY, originalCopilotTokenEndpoint);
        }

        if (originalCopilotAllowCustomTokenEndpoint == null) {
            System.clearProperty(COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY);
        } else {
            System.setProperty(COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY, originalCopilotAllowCustomTokenEndpoint);
        }
    }

    @Test
    @DisplayName("Interrupted Copilot model listing stops without starting fallback requests")
    void fetchModels_whenCopilotRequestIsInterrupted_stopsFallbackChain() throws Exception {
        var requests = new AtomicInteger();
        var requestStarted = new CountDownLatch(1);
        var releaseRequest = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            requests.incrementAndGet();
            requestStarted.countDown();
            try {
                releaseRequest.await(2, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        Thread worker = null;
        try {
            String baseUrl = "http://127.0.0.1:%d".formatted(server.getAddress().getPort());
            ProviderDescriptor descriptor = new ProviderDescriptor(
                    "GitHub Copilot",
                    AuthType.COPILOT_OAUTH,
                    null,
                    null,
                    baseUrl,
                    emptyList(),
                    ProviderCapabilities.chatAndModels(),
                    UnaryOperator.identity()
            );
            ProviderRuntime runtime = new ProviderRuntime(descriptor, null, baseUrl, "copilot-token", null);
            var models = new AtomicReference<List<String>>();

            worker = Thread.startVirtualThread(() -> models.set(subject.fetchModels(runtime)));
            assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue();
            worker.interrupt();
            releaseRequest.countDown();
            worker.join(2_000);

            assertThat(worker.isAlive()).isFalse();
            assertThat(worker.isInterrupted()).isTrue();
            assertThat(models.get()).isEmpty();
            assertThat(requests).hasValue(1);
        } finally {
            releaseRequest.countDown();
            if (worker != null) {
                worker.interrupt();
                worker.join(2_000);
            }
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Copilot model listing exchanges GitHub OAuth token and returns modern GPT models")
    void fetchModels_whenCopilotUsesGithubOAuthToken_exchangesTokenAndReturnsModernModels() throws Exception {
        var metadataStore = new CopilotModelMetadataStore(tempDir.resolve("oauth-exchange-metadata"));
        var subject = new OpenAiModelCatalogClient(metadataStore);
        AtomicInteger tokenExchangeCalls = new AtomicInteger();
        var modelAuthorizationHeaders = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/copilot_internal/v2/token", exchange -> {
            tokenExchangeCalls.incrementAndGet();
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            String body = "token gho_generic_token_success".equals(authHeader)
                    ? "{\"token\":\"copilot-internal-token\"}"
                    : "{\"token\":\"\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });

        server.createContext("/models", exchange -> {
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            modelAuthorizationHeaders.add(authHeader);
            String integrationHeader = exchange.getRequestHeaders().getFirst("Copilot-Integration-Id");

            String body;
            if ("Bearer copilot-internal-token".equals(authHeader)
                    && "copilot-developer-cli".equals(integrationHeader)) {
                body = "{\"data\":[{\"id\":\"gpt-5.4\",\"supported_endpoints\":[\"/chat/completions\"]},{\"id\":\"gpt-5.4-mini\",\"supported_endpoints\":[\"/responses\"]},{\"id\":\"claude-messages-only\",\"supported_endpoints\":[\"/v1/messages\"]},{\"id\":\"gpt-4o\"}]}";
            } else {
                body = "{\"data\":[{\"id\":\"gpt-3.5-turbo\"},{\"id\":\"gpt-4o\"}]}";
            }

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            System.setProperty(COPILOT_TOKEN_ENDPOINT_PROPERTY, "http://127.0.0.1:%d/copilot_internal/v2/token".formatted(port));
            System.setProperty(COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY, "true");

            ProviderDescriptor descriptor = new ProviderDescriptor(
                    "GitHub Copilot",
                    AuthType.COPILOT_OAUTH,
                    null,
                    null,
                    "http://127.0.0.1:%d".formatted(port),
                    emptyList(),
                    ProviderCapabilities.chatAndModels(),
                    UnaryOperator.identity());

            ProviderRuntime runtime = new ProviderRuntime(
                    descriptor,
                    null,
                    "http://127.0.0.1:%d".formatted(port),
                    "gho_generic_token_success",
                    null
            );

            List<String> firstModels = subject.fetchModels(runtime);
            List<String> secondModels = subject.fetchModels(runtime);

            assertThat(firstModels).contains("gpt-5.4", "gpt-5.4-mini", "gpt-4o");
            assertThat(firstModels).doesNotContain("gpt-3.5-turbo", "claude-messages-only");
            assertThat(secondModels).isEqualTo(firstModels);
            assertThat(tokenExchangeCalls.get()).isEqualTo(1);
            assertThat(modelAuthorizationHeaders)
                    .isNotEmpty()
                    .containsOnly("Bearer copilot-internal-token")
                    .noneMatch(header -> header.contains("gho_generic_token_success"));
            assertThat(metadataStore.supportedEndpoints("http://127.0.0.1:%d".formatted(port), "gpt-5.4"))
                    .containsExactly("/chat/completions");
            assertThat(metadataStore.supportedEndpoints("http://127.0.0.1:%d".formatted(port), "gpt-5.4-mini"))
                    .containsExactly("/responses");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Failed GitHub OAuth exchange never sends the raw token to a custom model endpoint")
    void fetchModels_whenGithubOAuthExchangeFails_doesNotSendRawTokenToModelEndpoint() throws Exception {
        var tokenAuthorizationHeaders = new CopyOnWriteArrayList<String>();
        var modelAuthorizationHeaders = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/copilot_internal/v2/token", exchange -> {
            tokenAuthorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] payload = "{\"token\":\"\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.createContext("/models", exchange -> {
            modelAuthorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            String baseUrl = "http://127.0.0.1:%d".formatted(port);
            System.setProperty(
                    COPILOT_TOKEN_ENDPOINT_PROPERTY,
                    "%s/copilot_internal/v2/token".formatted(baseUrl)
            );
            System.setProperty(COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY, "true");
            ProviderDescriptor descriptor = new ProviderDescriptor(
                    "GitHub Copilot",
                    AuthType.COPILOT_OAUTH,
                    null,
                    null,
                    baseUrl,
                    emptyList(),
                    ProviderCapabilities.chatAndModels(),
                    UnaryOperator.identity()
            );
            String freshToken = "gho_failed_exchange_%s".formatted(UUID.randomUUID());
            ProviderRuntime runtime = new ProviderRuntime(
                    descriptor,
                    null,
                    baseUrl,
                    freshToken,
                    null
            );

            List<String> models = subject.fetchModels(runtime);

            assertThat(models).isEmpty();
            assertThat(tokenAuthorizationHeaders).containsExactly("token %s".formatted(freshToken));
            assertThat(modelAuthorizationHeaders).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Unapproved Copilot token endpoint receives no request from a fresh OAuth exchange")
    void fetchModels_whenTokenEndpointIsUnapproved_usesTrustedEndpointWithoutLeakingCredential() throws Exception {
        var maliciousEndpointRequests = new AtomicInteger();
        var maliciousAuthorizationHeaders = new CopyOnWriteArrayList<String>();
        HttpServer maliciousServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        maliciousServer.createContext("/copilot_internal/v2/token", exchange -> {
            maliciousEndpointRequests.incrementAndGet();
            maliciousAuthorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        maliciousServer.start();

        try {
            String maliciousBaseUrl = "http://127.0.0.1:%d".formatted(maliciousServer.getAddress().getPort());
            System.setProperty(
                    COPILOT_TOKEN_ENDPOINT_PROPERTY,
                    "%s/copilot_internal/v2/token".formatted(maliciousBaseUrl)
            );
            System.clearProperty(COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY);

            var requestedUris = new CopyOnWriteArrayList<URI>();
            var requestedAuthorizationHeaders = new CopyOnWriteArrayList<String>();
            HttpClient httpClient = mock(HttpClient.class);
            HttpResponse<String> unauthorizedResponse = mock(HttpResponse.class);
            when(unauthorizedResponse.statusCode()).thenReturn(401);
            when(httpClient.send(
                    any(HttpRequest.class),
                    org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()
            )).thenAnswer(invocation -> {
                HttpRequest request = invocation.getArgument(0);
                requestedUris.add(request.uri());
                requestedAuthorizationHeaders.add(request.headers().firstValue("Authorization").orElse(null));
                return unauthorizedResponse;
            });

            var subject = new OpenAiModelCatalogClient(
                    new CopilotModelMetadataStore(tempDir.resolve("unapproved-endpoint-metadata")),
                    httpClient
            );
            ProviderDescriptor descriptor = new ProviderDescriptor(
                    "GitHub Copilot",
                    AuthType.COPILOT_OAUTH,
                    null,
                    null,
                    maliciousBaseUrl,
                    emptyList(),
                    ProviderCapabilities.chatAndModels(),
                    UnaryOperator.identity()
            );
            String freshToken = "gho_unapproved_endpoint_%s".formatted(UUID.randomUUID());
            ProviderRuntime runtime = new ProviderRuntime(
                    descriptor,
                    null,
                    maliciousBaseUrl,
                    freshToken,
                    null
            );

            assertThat(subject.fetchModels(runtime)).isEmpty();
            assertThat(requestedUris).containsExactly(
                    URI.create("https://api.github.com/copilot_internal/v2/token")
            );
            assertThat(requestedAuthorizationHeaders).containsExactly("token %s".formatted(freshToken));
            assertThat(maliciousEndpointRequests).hasValue(0);
            assertThat(maliciousAuthorizationHeaders).isEmpty();
        } finally {
            maliciousServer.stop(0);
        }
    }

    @Test
    @DisplayName("Copilot model listing preserves previously known endpoint metadata when degraded refresh omits endpoints")
    void fetchModels_whenDegradedCopilotRefreshOmitsEndpoints_keepsExistingEndpointMetadata() throws Exception {
        var metadataStore = new CopilotModelMetadataStore(tempDir.resolve("degraded-refresh-metadata"));
        var subject = new OpenAiModelCatalogClient(metadataStore);
        var authorizationHeader = new AtomicReference<String>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String body = """
                    {"data":[{"id":"gpt-4o","supported_endpoints":["/chat/completions"]}]}
                    """;
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            String baseUrl = "http://127.0.0.1:%d".formatted(port);
            assertThat(metadataStore.updateIfGenerationCurrent(
                    metadataStore.currentGeneration(),
                    baseUrl,
                    List.of(new CopilotModelMetadataStore.ModelMetadata("gpt-5.4-mini", List.of("/responses")))
            )).isTrue();

            System.setProperty(COPILOT_TOKEN_ENDPOINT_PROPERTY, "http://127.0.0.1:%d/copilot_internal/v2/token".formatted(port));
            System.setProperty(COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY, "true");

            ProviderDescriptor descriptor = new ProviderDescriptor(
                    "GitHub Copilot",
                    AuthType.COPILOT_OAUTH,
                    null,
                    null,
                    baseUrl,
                    emptyList(),
                    ProviderCapabilities.chatAndModels(),
                    UnaryOperator.identity());

            String sessionToken = "tid=test-tenant;exp=4102444800";
            ProviderRuntime runtime = new ProviderRuntime(
                    descriptor,
                    null,
                    baseUrl,
                    sessionToken,
                    null
            );

            List<String> models = subject.fetchModels(runtime);

            assertThat(models).contains("gpt-4o");
            assertThat(authorizationHeader).hasValue("Bearer %s".formatted(sessionToken));
            assertThat(metadataStore.supportedEndpoints(baseUrl, "gpt-5.4-mini"))
                    .containsExactly("/responses");
            assertThat(metadataStore.supportedEndpoints(baseUrl, "gpt-4o"))
                    .containsExactly("/chat/completions");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Copilot metadata fetched before an auth clear cannot repopulate the store")
    void fetchModels_whenAuthClearsDuringRequest_rejectsStaleEndpointMetadata() throws Exception {
        var metadataStore = new CopilotModelMetadataStore(tempDir.resolve("auth-clear-metadata"));
        var subject = new OpenAiModelCatalogClient(metadataStore);
        var requestStarted = new CountDownLatch(1);
        var releaseResponse = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            requestStarted.countDown();
            try {
                releaseResponse.await(2, TimeUnit.SECONDS);
                byte[] payload = """
                        {"data":[{"id":"stale-model","supported_endpoints":["/responses"]}]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        Thread fetchThread = null;

        try {
            String baseUrl = "http://127.0.0.1:%d".formatted(server.getAddress().getPort());
            ProviderDescriptor descriptor = new ProviderDescriptor(
                    "GitHub Copilot",
                    AuthType.COPILOT_OAUTH,
                    null,
                    null,
                    baseUrl,
                    emptyList(),
                    ProviderCapabilities.chatAndModels(),
                    UnaryOperator.identity()
            );
            ProviderRuntime runtime = new ProviderRuntime(
                    descriptor,
                    null,
                    baseUrl,
                    "tid=test-tenant;exp=4102444800",
                    null
            );
            var models = new AtomicReference<List<String>>();
            fetchThread = Thread.startVirtualThread(() -> models.set(subject.fetchModels(runtime)));

            assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(metadataStore.clear()).isTrue();
            releaseResponse.countDown();
            fetchThread.join(TimeUnit.SECONDS.toMillis(2));

            assertThat(fetchThread.isAlive()).isFalse();
            assertThat(models.get()).contains("stale-model");
            assertThat(metadataStore.supportedEndpoints(baseUrl, "stale-model")).isEmpty();
        } finally {
            releaseResponse.countDown();
            if (fetchThread != null) {
                fetchThread.interrupt();
                fetchThread.join(TimeUnit.SECONDS.toMillis(2));
            }
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Copilot model listing filters websocket-only endpoint models from selection")
    void fetchModels_whenCopilotModelIsWebsocketOnly_excludesItFromSelection() throws Exception {
        var metadataStore = new CopilotModelMetadataStore(tempDir.resolve("websocket-filter-metadata"));
        var subject = new OpenAiModelCatalogClient(metadataStore);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            String body = """
                    {"data":[
                      {"id":"ws-only-model","supported_endpoints":["ws:/responses"]},
                      {"id":"gpt-5.4-mini","supported_endpoints":["/responses"]}
                    ]}
                    """;
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            ProviderDescriptor descriptor = new ProviderDescriptor(
                    "GitHub Copilot",
                    AuthType.COPILOT_OAUTH,
                    null,
                    null,
                    "http://127.0.0.1:%d".formatted(port),
                    emptyList(),
                    ProviderCapabilities.chatAndModels(),
                    UnaryOperator.identity());

            ProviderRuntime runtime = new ProviderRuntime(
                    descriptor,
                    null,
                    "http://127.0.0.1:%d".formatted(port),
                    "copilot-token",
                    null
            );

            List<String> models = subject.fetchModels(runtime);

            assertThat(models).containsExactly("gpt-5.4-mini");
            assertThat(metadataStore.supportedEndpoints("http://127.0.0.1:%d".formatted(port), "ws-only-model")).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Copilot model listing honors model picker flags and suppresses legacy aliases")
    void fetchModels_whenCopilotModelPickerFlagsPresent_filtersOutLegacyModels() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            String body = """
                    {
                      "data": [
                        {"id":"gpt-4o","model_picker_enabled":true},
                        {"id":"gpt-4o-2024-11-20","model_picker_enabled":false},
                        {"id":"gpt-4o-mini","model_picker_enabled":true},
                        {"id":"gpt-4o-mini-2024-07-18","model_picker_enabled":false},
                        {"id":"gpt-3.5-turbo","model_picker_enabled":false},
                        {"id":"gpt-5.4","model_picker_enabled":true}
                      ]
                    }
                    """;
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            ProviderDescriptor descriptor = new ProviderDescriptor(
                    "GitHub Copilot",
                    AuthType.COPILOT_OAUTH,
                    null,
                    null,
                    "http://127.0.0.1:%d".formatted(port),
                    emptyList(),
                    ProviderCapabilities.chatAndModels(),
                    UnaryOperator.identity());

            ProviderRuntime runtime = new ProviderRuntime(
                    descriptor,
                    null,
                    "http://127.0.0.1:%d".formatted(port),
                    "copilot-token",
                    null
            );

            List<String> models = subject.fetchModels(runtime);

            assertThat(models).contains("gpt-5.4", "gpt-4o", "gpt-4o-mini");
            assertThat(models).doesNotContain("gpt-4o-2024-11-20", "gpt-4o-mini-2024-07-18", "gpt-3.5-turbo");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Copilot model listing keeps non-legacy models when model_picker_enabled set is too narrow")
    void fetchModels_whenCopilotModelPickerHasSingleModel_keepsCanonicalNonLegacyModels() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            String body = """
                    {
                      "data": [
                        {"id":"gpt-4o-mini","model_picker_enabled":false},
                        {"id":"gpt-4o-mini-2024-07-18","model_picker_enabled":false},
                        {"id":"gpt-4o","model_picker_enabled":true},
                        {"id":"gpt-4o-2024-11-20","model_picker_enabled":false},
                        {"id":"gpt-3.5-turbo","model_picker_enabled":false}
                      ]
                    }
                    """;
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            ProviderDescriptor descriptor = new ProviderDescriptor(
                    "GitHub Copilot",
                    AuthType.COPILOT_OAUTH,
                    null,
                    null,
                    "http://127.0.0.1:%d".formatted(port),
                    emptyList(),
                    ProviderCapabilities.chatAndModels(),
                    UnaryOperator.identity());

            ProviderRuntime runtime = new ProviderRuntime(
                    descriptor,
                    null,
                    "http://127.0.0.1:%d".formatted(port),
                    "copilot-token",
                    null
            );

            List<String> models = subject.fetchModels(runtime);

            assertThat(models).contains("gpt-4o", "gpt-4o-mini");
            assertThat(models).doesNotContain("gpt-4o-2024-11-20", "gpt-4o-mini-2024-07-18", "gpt-3.5-turbo");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Untrusted Copilot token endpoint overrides require explicit opt-in")
    void copilotTokenEndpoint_whenOverrideIsUntrusted_requiresExplicitOptIn() {
        String customEndpoint = "http://127.0.0.1:8080/copilot_internal/v2/token";
        System.setProperty(COPILOT_TOKEN_ENDPOINT_PROPERTY, customEndpoint);
        System.clearProperty(COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY);

        assertThat(subject.copilotTokenEndpoint())
                .isEqualTo("https://api.github.com/copilot_internal/v2/token");

        System.setProperty(COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY, "true");

        assertThat(subject.copilotTokenEndpoint()).isEqualTo(customEndpoint);
    }

    @Test
    @DisplayName("Codex OAuth provider retries the tolerant HTTP path when the SDK catalog is empty")
    void fetchModels_whenCodexSdkListingIsEmpty_usesHttpFallback() throws Exception {
        var requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            boolean firstRequest = requests.incrementAndGet() == 1;
            byte[] payload = (firstRequest
                    ? """
                            {"object":"list","data":[]}
                            """
                    : """
                            {
                              "object": "list",
                              "data": [
                                {"id":"gpt-4o","object":"model","created":1,"owned_by":"openai"}
                              ]
                            }
                            """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            ProviderDescriptor descriptor = new ProviderDescriptor(
                    "OpenAI Codex",
                    AuthType.CODEX_OAUTH,
                    null,
                    null,
                    "http://127.0.0.1:%d".formatted(port),
                    emptyList(),
                    ProviderCapabilities.chatAndModels(),
                    UnaryOperator.identity());

            ProviderRuntime runtime = new ProviderRuntime(
                    descriptor,
                    null,
                    "http://127.0.0.1:%d".formatted(port),
                    "test-token",
                    null
            );

            List<String> models = subject.fetchModels(runtime);

            assertThat(models).containsExactly("gpt-4o");
            assertThat(requests).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Codex OAuth provider leaves local fallback models to the cache overlay")
    void fetchModels_whenCodexApiListingFails_returnsEmptyRemoteCatalog() throws Exception {
        var requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            requests.incrementAndGet();
            byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        try {
            String endpoint = "http://127.0.0.1:%d".formatted(server.getAddress().getPort());
            ProviderDescriptor descriptor = new ProviderDescriptor(
                    "OpenAI Codex",
                    AuthType.CODEX_OAUTH,
                    null,
                    null,
                    endpoint,
                    emptyList(),
                    ProviderCapabilities.chatAndModels(),
                    UnaryOperator.identity());

            ProviderRuntime runtime = new ProviderRuntime(
                    descriptor,
                    null,
                    endpoint,
                    "DUMMY_CODEX_BEARER_TOKEN_FOR_TESTS",
                    null
            );

            List<String> models = subject.fetchModels(runtime);

            assertThat(models).isEmpty();
            assertThat(requests).hasValueGreaterThan(0);
        } finally {
            server.stop(0);
        }
    }
}
