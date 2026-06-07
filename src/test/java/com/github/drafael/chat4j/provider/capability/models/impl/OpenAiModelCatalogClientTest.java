package com.github.drafael.chat4j.provider.capability.models.impl;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.CopilotModelMetadataStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Collections.emptyList;

class OpenAiModelCatalogClientTest {

    private static final OpenAiModelCatalogClient subject = new OpenAiModelCatalogClient();
    private static final String COPILOT_TOKEN_ENDPOINT_PROPERTY = "chat4j.copilot.tokenEndpoint";
    private static final String COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY = "chat4j.copilot.allowCustomTokenEndpoint";

    private String originalUserHome;
    private String originalCopilotTokenEndpoint;
    private String originalCopilotAllowCustomTokenEndpoint;

    @AfterEach
    void tearDown() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }

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
    @DisplayName("Copilot model listing exchanges GitHub OAuth token and returns modern GPT models")
    void fetchModels_whenCopilotUsesGithubOAuthToken_exchangesTokenAndReturnsModernModels() throws Exception {
        var metadataStore = new CopilotModelMetadataStore(Files.createTempDirectory("copilot-model-metadata"));
        var subject = new OpenAiModelCatalogClient(metadataStore);
        AtomicInteger tokenExchangeCalls = new AtomicInteger();
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
            originalCopilotTokenEndpoint = System.getProperty(COPILOT_TOKEN_ENDPOINT_PROPERTY);
            originalCopilotAllowCustomTokenEndpoint = System.getProperty(COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY);
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
            assertThat(metadataStore.supportedEndpoints("http://127.0.0.1:%d".formatted(port), "gpt-5.4"))
                    .containsExactly("/chat/completions");
            assertThat(metadataStore.supportedEndpoints("http://127.0.0.1:%d".formatted(port), "gpt-5.4-mini"))
                    .containsExactly("/responses");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Copilot model listing preserves previously known endpoint metadata when degraded refresh omits endpoints")
    void fetchModels_whenDegradedCopilotRefreshOmitsEndpoints_keepsExistingEndpointMetadata() throws Exception {
        var metadataStore = new CopilotModelMetadataStore(Files.createTempDirectory("copilot-model-metadata"));
        var subject = new OpenAiModelCatalogClient(metadataStore);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            String body = "{\"data\":[{\"id\":\"gpt-4o\"}]}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        try {
            originalCopilotTokenEndpoint = System.getProperty(COPILOT_TOKEN_ENDPOINT_PROPERTY);
            originalCopilotAllowCustomTokenEndpoint = System.getProperty(COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY);
            int port = server.getAddress().getPort();
            String baseUrl = "http://127.0.0.1:%d".formatted(port);
            metadataStore.update(
                    baseUrl,
                    List.of(new CopilotModelMetadataStore.ModelMetadata("gpt-5.4-mini", List.of("/responses")))
            );

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

            ProviderRuntime runtime = new ProviderRuntime(
                    descriptor,
                    null,
                    baseUrl,
                    "gho_generic_token_failure",
                    null
            );

            List<String> models = subject.fetchModels(runtime);

            assertThat(models).contains("gpt-4o");
            assertThat(metadataStore.supportedEndpoints(baseUrl, "gpt-5.4-mini"))
                    .containsExactly("/responses");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Copilot model listing filters websocket-only endpoint models from selection")
    void fetchModels_whenCopilotModelIsWebsocketOnly_excludesItFromSelection() throws Exception {
        var metadataStore = new CopilotModelMetadataStore(Files.createTempDirectory("copilot-model-metadata"));
        var subject = new OpenAiModelCatalogClient(metadataStore);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            String body = "{\"data\":[{\"id\":\"ws-only-model\",\"supported_endpoints\":[\"ws:/responses\"]},{\"id\":\"gpt-5.4-mini\",\"supported_endpoints\":[\"/responses\"]}]}";
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
    @DisplayName("Copilot model listing ignores untrusted token endpoint override unless explicitly allowed")
    void fetchModels_whenCopilotTokenEndpointOverrideIsUntrusted_ignoresOverrideByDefault() throws Exception {
        AtomicInteger tokenExchangeCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/copilot_internal/v2/token", exchange -> {
            tokenExchangeCalls.incrementAndGet();
            byte[] payload = "{\"token\":\"copilot-internal-token\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });

        server.createContext("/models", exchange -> {
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            String body = "Bearer gho_generic_token_untrusted".equals(authHeader)
                    ? "{\"data\":[{\"id\":\"gpt-4o\"}]}"
                    : "{\"data\":[{\"id\":\"gpt-5.4\"}]}";

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        try {
            originalCopilotTokenEndpoint = System.getProperty(COPILOT_TOKEN_ENDPOINT_PROPERTY);
            originalCopilotAllowCustomTokenEndpoint = System.getProperty(COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY);
            int port = server.getAddress().getPort();
            System.setProperty(COPILOT_TOKEN_ENDPOINT_PROPERTY, "http://127.0.0.1:%d/copilot_internal/v2/token".formatted(port));
            System.clearProperty(COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY);

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
                    "gho_generic_token_untrusted",
                    null
            );

            List<String> models = subject.fetchModels(runtime);

            assertThat(models).contains("gpt-4o");
            assertThat(models).doesNotContain("gpt-5.4");
            assertThat(tokenExchangeCalls.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Codex OAuth provider merges local codex cache with API model listing")
    void fetchModels_whenCodexApiListingSucceeds_mergesLocalCodexModelsFromCache() throws Exception {
        originalUserHome = System.getProperty("user.home");
        Path tempHome = Files.createTempDirectory("chat4j-codex-home");
        System.setProperty("user.home", tempHome.toString());

        Path codexDir = tempHome.resolve(".codex");
        Files.createDirectories(codexDir);
        Files.writeString(codexDir.resolve("models_cache.json"), """
                {
                  "models": [
                    {"slug": "gpt-5.5"},
                    {"slug": "gpt-5.4"}
                  ]
                }
                """);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            byte[] payload = """
                    {
                      "object": "list",
                      "data": [
                        {"id":"gpt-4o","object":"model","created":1,"owned_by":"openai"}
                      ]
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

            assertThat(models).contains("gpt-5.5", "gpt-5.4", "gpt-4o");
            assertThat(models).doesNotHaveDuplicates();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Codex OAuth provider falls back to local codex models cache when API listing fails")
    void fetchModels_whenCodexApiListingFails_returnsLocalCodexModelsFromCache() throws Exception {
        originalUserHome = System.getProperty("user.home");
        Path tempHome = Files.createTempDirectory("chat4j-codex-home");
        System.setProperty("user.home", tempHome.toString());

        Path codexDir = tempHome.resolve(".codex");
        Files.createDirectories(codexDir);
        Files.writeString(codexDir.resolve("models_cache.json"), """
                {
                  "models": [
                    {"slug": "gpt-5-codex"},
                    {"slug": "gpt-5-codex-mini"},
                    {"slug": "gpt-5-codex"}
                  ]
                }
                """);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
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

            assertThat(models).contains("gpt-5-codex", "gpt-5-codex-mini");
            assertThat(models).doesNotHaveDuplicates();
        } finally {
            server.stop(0);
        }
    }
}
