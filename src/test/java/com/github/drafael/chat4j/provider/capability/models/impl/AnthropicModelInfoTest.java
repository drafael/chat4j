package com.github.drafael.chat4j.provider.capability.models.impl;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicModelInfoTest {

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
