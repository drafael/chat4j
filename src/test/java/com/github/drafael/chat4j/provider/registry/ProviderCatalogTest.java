package com.github.drafael.chat4j.provider.registry;

import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderCatalogTest {

    @AfterEach
    void tearDown() {
        CredentialResolver.init(Map.of());
    }

    @Test
    @DisplayName("Provider catalog uses dynamic model loading with no hardcoded seed models")
    void allProviders_whenCatalogInitialized_hasNoSeedModels() {
        var subject = new ProviderCatalog();

        assertThat(subject.allProviders())
                .allSatisfy(providerDefinition -> assertThat(providerDefinition.seedModels()).isEmpty());
    }

    @Test
    @DisplayName("Provider catalog includes OAuth CLI providers for Codex and Copilot")
    void allProviders_whenCatalogInitialized_includesCliOauthProviders() {
        var subject = new ProviderCatalog();

        assertThat(subject.allProviders())
                .extracting(ProviderDefinition::name)
                .contains("OpenAI Codex", "GitHub Copilot");
    }

    @Test
    @DisplayName("Provider catalog includes local providers for LM Studio and Ollama")
    void allProviders_whenCatalogInitialized_includesLocalProviders() {
        var subject = new ProviderCatalog();

        assertThat(subject.allProviders())
                .extracting(ProviderDefinition::name)
                .contains("LM Studio", "Ollama");
    }

    @Test
    @DisplayName("Anthropic fetcher loads model names dynamically from API and normalizes /v1 base URLs")
    void createFetcher_whenAnthropicProvider_fetchesModelNamesFromApi() throws Exception {
        var responseJson = """
                {
                  "data": [
                    {
                      "id": "claude-3-7-sonnet-20250219",
                      "created_at": "2025-02-19T00:00:00Z",
                      "display_name": "Claude 3.7 Sonnet",
                      "type": "model"
                    },
                    {
                      "id": "claude-3-5-haiku-20241022",
                      "created_at": "2024-10-22T00:00:00Z",
                      "display_name": "Claude 3.5 Haiku",
                      "type": "model"
                    }
                  ],
                  "first_id": "claude-3-7-sonnet-20250219",
                  "last_id": "claude-3-5-haiku-20241022",
                  "has_more": false
                }
                """;

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            CredentialResolver.init(Map.of("ANTHROPIC_API_KEY", "test-api-key"));

            var subject = new ProviderCatalog();
            var fetcher = subject.createFetcher(
                    "Anthropic",
                    "ANTHROPIC_API_KEY",
                    "http://127.0.0.1:" + port + "/v1",
                    List.of());

            List<String> models = fetcher.fetchModels();

            assertThat(models).containsExactly(
                    "claude-3-7-sonnet-20250219",
                    "claude-3-5-haiku-20241022"
            );
        } finally {
            server.stop(0);
        }
    }
}
