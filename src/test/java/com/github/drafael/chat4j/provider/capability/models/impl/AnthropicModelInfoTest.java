package com.github.drafael.chat4j.provider.capability.models.impl;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicModelInfoTest {

    @Test
    @DisplayName("Anthropic model discovery follows the SDK cursor through every catalog page")
    void fetchModels_catalogHasMultiplePages_returnsModelsBeyondTheFirstPage() throws Exception {
        var requests = new AtomicInteger();
        var lastQuery = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            int requestNumber = requests.incrementAndGet();
            String query = exchange.getRequestURI().getRawQuery();
            lastQuery.set(query);
            String page = switch (requestNumber) {
                case 1 -> """
                        {"data":[{"id":"claude-first","type":"model","display_name":"First",
                          "created_at":"2026-11-09T00:00:00Z"}],
                          "has_more":true,"first_id":"claude-first","last_id":"claude-first"}
                        """;
                case 2 -> """
                        {"data":[{"id":"claude-second","type":"model","display_name":"Second",
                          "created_at":"2026-10-01T00:00:00Z"}],
                          "has_more":false,"first_id":"claude-second","last_id":"claude-second"}
                        """;
                default -> """
                        {"data":[],"has_more":false}
                        """;
            };
            byte[] body = page.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:%d".formatted(server.getAddress().getPort());
            var descriptor = new ProviderDescriptor("Anthropic", AuthType.ENV_VAR, null, null, baseUrl, null, null, null);
            var subject = new AnthropicModelCatalogClient();

            assertThat(subject.fetchModels(new ProviderRuntime(descriptor, null, baseUrl, "test-key", null)))
                    .containsExactlyInAnyOrder("claude-first", "claude-second");
            assertThat(requests).hasValue(2);
            assertThat(lastQuery.get()).contains("after_id=claude-first");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Anthropic SDK metadata supplies display names and optional token limits without invented prices")
    void fetchModelInfos_providerReturnsLimits_preservesSdkMetadata() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            String page = exchange.getRequestURI().getRawQuery() == null ? """
                    {"data":[{"id":"claude-test","type":"model","display_name":"Claude Test",
                      "created_at":"2026-01-01T00:00:00Z","max_input_tokens":200000,"max_tokens":64000}],
                      "has_more":false,"first_id":"claude-test","last_id":"claude-test"}
                    """ : """
                    {"data":[],"has_more":false,"first_id":null,"last_id":null}
                    """;
            byte[] body = page.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:%d".formatted(server.getAddress().getPort());
            var descriptor = new ProviderDescriptor("Anthropic", AuthType.ENV_VAR, null, null, baseUrl, null, null, null);
            var subject = new AnthropicModelCatalogClient();

            var info = subject.fetchModelInfos(new ProviderRuntime(descriptor, null, baseUrl, "test-key", null))
                    .get("claude-test");

            assertThat(info.displayName()).isEqualTo("Claude Test");
            assertThat(info.maxInputTokens()).isEqualTo(200000L);
            assertThat(info.maxOutputTokens()).isEqualTo(64000L);
            assertThat(info.contextWindowTokens()).isNull();
            assertThat(info.inputUsdPerMillion()).isNull();
            assertThat(info.outputUsdPerMillion()).isNull();
        } finally {
            server.stop(0);
        }
    }
}
