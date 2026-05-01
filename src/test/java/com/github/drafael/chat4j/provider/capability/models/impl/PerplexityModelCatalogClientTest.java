package com.github.drafael.chat4j.provider.capability.models.impl;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PerplexityModelCatalogClientTest {

    @Test
    @DisplayName("Perplexity model catalog extracts Sonar models from OpenAI-compatible responses")
    void fetchModels_whenEndpointReturnsOpenAiFormat_extractsPerplexitySonarModels() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] body = """
                    {
                      "object": "list",
                      "data": [
                        {"id": "anthropic/claude-sonnet-4-6", "owned_by": "anthropic"},
                        {"id": "perplexity/sonar", "owned_by": "perplexity"},
                        {"id": "perplexity/sonar-pro", "owned_by": "perplexity"},
                        {"id": "sonar-deep-research", "owned_by": "perplexity"}
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var subject = new PerplexityModelCatalogClient();
            List<String> models = subject.fetchModels(runtime("http://127.0.0.1:%d".formatted(server.getAddress().getPort())));

            assertThat(models)
                    .contains("sonar", "sonar-pro", "sonar-deep-research")
                    .doesNotContain("anthropic/claude-sonnet-4-6");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Perplexity model catalog returns fallback models when the endpoint is unavailable")
    void fetchModels_whenEndpointUnavailable_returnsFallbackModels() {
        var subject = new PerplexityModelCatalogClient();

        List<String> models = subject.fetchModels(runtime("http://127.0.0.1:1"));

        assertThat(models).containsExactly("sonar", "sonar-pro");
    }

    private ProviderRuntime runtime(String baseUrl) {
        return new ProviderRuntime(
                new ProviderDescriptor(
                        "Perplexity",
                        AuthType.ENV_VAR,
                        "PERPLEXITY_API_KEY",
                        null,
                        null,
                        "https://api.perplexity.ai",
                        List.of("sonar", "sonar-pro"),
                        ProviderCapabilities.chatModelsAndNativeWebSearch(),
                        value -> value
                ),
                "PERPLEXITY_API_KEY",
                baseUrl,
                "test-key",
                null
        );
    }
}
