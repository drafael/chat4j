package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PerplexityChatCompletionClientTest {

    @Test
    @DisplayName("Perplexity chat client de-duplicates sources by normalized URL")
    void streamCompletion_whenSourcesDifferByTrailingSlash_deduplicatesSources() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] body = """
                    {
                      "choices": [{"message": {"content": "answer"}}],
                      "search_results": [
                        {"title": "One", "url": "https://example.test/source/"},
                        {"title": "Duplicate", "url": "https://example.test/source"}
                      ],
                      "citations": ["https://example.test/source#"]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            PerplexityChatCompletionClient subject = new PerplexityChatCompletionClient();
            StringBuilder output = new StringBuilder();

            subject.streamCompletion(
                    runtime("http://127.0.0.1:%d".formatted(server.getAddress().getPort())),
                    List.of(Message.user("question")),
                    ReasoningLevel.OFF,
                    output::append,
                    ignored -> {
                    },
                    () -> false,
                    ignored -> {
                    },
                    () -> {
                    }
            );

            assertThat(output.toString()).contains("Sources:");
            assertThat(output.toString().split("https://example.test/source", -1)).hasSize(3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Perplexity chat client appends chat completions path when base URL ends with v1")
    void streamCompletion_whenBaseUrlEndsWithV1_postsToChatCompletionsEndpoint() throws Exception {
        AtomicReference<String> requestedPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            byte[] body = """
                    {
                      "choices": [{"message": {"content": "answer"}}],
                      "search_results": []
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            PerplexityChatCompletionClient subject = new PerplexityChatCompletionClient();
            StringBuilder output = new StringBuilder();

            subject.streamCompletion(
                    runtime("http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort())),
                    List.of(Message.user("question")),
                    ReasoningLevel.OFF,
                    output::append,
                    ignored -> {
                    },
                    () -> false,
                    ignored -> {
                    },
                    () -> {
                    }
            );

            assertThat(requestedPath.get()).isEqualTo("/v1/chat/completions");
            assertThat(output).hasToString("answer");
        } finally {
            server.stop(0);
        }
    }

    private ProviderRuntime runtime(String baseUrl) {
        return new ProviderRuntime(
                new ProviderDescriptor(
                        "Perplexity",
                        AuthType.ENV_VAR,
                        "PERPLEXITY_API_KEY",
                        null,
                        "https://api.perplexity.ai",
                        List.of("sonar"),
                        ProviderCapabilities.chatModelsAndNativeWebSearch(),
                        value -> value
                ),
                "PERPLEXITY_API_KEY",
                baseUrl,
                "test-key",
                "sonar"
        );
    }
}
