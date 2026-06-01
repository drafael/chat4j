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
import java.util.concurrent.CopyOnWriteArrayList;
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
            assertThat(output.toString()).contains("1. [One](https://example.test/source)");
            assertThat(output.toString().split("https://example.test/source", -1)).hasSize(3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Perplexity chat client collapses adjacent duplicate inline citations")
    void streamCompletion_whenResponseRepeatsSameCitation_collapsesDuplicateInlineCitations() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] body = """
                    {
                      "choices": [{"message": {"content": "answer [1][1] next [1] [1]"}}],
                      "search_results": [{"title": "One", "url": "https://example.test/source"}]
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

            assertThat(output.toString()).contains("answer [1](https://example.test/source) next [1](https://example.test/source)");
            assertThat(output.toString().split("https://example.test/source", -1)).hasSize(4);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Perplexity chat client leaves think tags for the model-agnostic chat parser")
    void streamCompletion_whenResponseContainsThinkTags_preservesTagsForChatParser() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] body = """
                    {
                      "choices": [{"message": {"content": "<think>private reasoning</think>visible answer"}}],
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
            StringBuilder thinking = new StringBuilder();

            subject.streamCompletion(
                    runtime("http://127.0.0.1:%d".formatted(server.getAddress().getPort())),
                    List.of(Message.user("question")),
                    ReasoningLevel.MEDIUM,
                    output::append,
                    thinking::append,
                    () -> false,
                    ignored -> {
                    },
                    () -> {
                    }
            );

            assertThat(output).hasToString("<think>private reasoning</think>visible answer");
            assertThat(thinking).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Perplexity chat client preserves think tags even when reasoning output is disabled")
    void streamCompletion_whenReasoningDisabledAndResponseContainsThinkTags_preservesTagsForChatParser() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] body = """
                    {
                      "choices": [{"message": {"content": "<think>private reasoning</think>visible answer"}}],
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
            StringBuilder thinking = new StringBuilder();

            subject.streamCompletion(
                    runtime("http://127.0.0.1:%d".formatted(server.getAddress().getPort())),
                    List.of(Message.user("question")),
                    ReasoningLevel.OFF,
                    output::append,
                    thinking::append,
                    () -> false,
                    ignored -> {
                    },
                    () -> {
                    }
            );

            assertThat(output).hasToString("<think>private reasoning</think>visible answer");
            assertThat(thinking).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Perplexity deep research uses async Sonar endpoint")
    void streamCompletion_whenModelIsDeepResearch_pollsAsyncSonarEndpoint() throws Exception {
        List<String> requestedPaths = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/async/sonar", exchange -> {
            requestedPaths.add(exchange.getRequestURI().getPath());
            byte[] body = switch (exchange.getRequestMethod()) {
                case "POST" -> {
                    String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    assertThat(requestBody).contains("\"request\":");
                    assertThat(requestBody).contains("\"model\":\"sonar-deep-research\"");
                    yield """
                            {
                              "id": "async-123",
                              "model": "sonar-deep-research",
                              "created_at": 123,
                              "status": "CREATED"
                            }
                            """.getBytes(StandardCharsets.UTF_8);
                }
                case "GET" -> """
                        {
                          "id": "async-123",
                          "model": "sonar-deep-research",
                          "completed_at": 456,
                          "status": "COMPLETED",
                          "response": {
                            "choices": [{"message": {"content": "deep answer"}}],
                            "search_results": [{"title": "Source", "url": "https://example.test/source"}]
                          }
                        }
                        """.getBytes(StandardCharsets.UTF_8);
                default -> throw new AssertionError("Unexpected method: %s".formatted(exchange.getRequestMethod()));
            };
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
                    runtime("http://127.0.0.1:%d".formatted(server.getAddress().getPort()), "sonar-deep-research"),
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

            assertThat(requestedPaths).containsExactly("/v1/async/sonar", "/v1/async/sonar/async-123");
            assertThat(output.toString()).contains("deep answer");
            assertThat(output.toString()).contains("Sources:");
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
        return runtime(baseUrl, "sonar");
    }

    private ProviderRuntime runtime(String baseUrl, String model) {
        return new ProviderRuntime(
                new ProviderDescriptor(
                        "Perplexity",
                        AuthType.ENV_VAR,
                        "PERPLEXITY_API_KEY",
                        null,
                        "https://api.perplexity.ai",
                        List.of("sonar", "sonar-deep-research"),
                        ProviderCapabilities.chatModelsAndNativeWebSearch(),
                        value -> value
                ),
                "PERPLEXITY_API_KEY",
                baseUrl,
                "test-key",
                model
        );
    }
}
