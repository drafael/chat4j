package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.WebSearchSource;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentTestSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepSeekAnthropicWebSearchClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Anthropic-compatible streaming sends native search and emits all distinct consulted sources")
    void streamCompletion_whenSseContainsSearchResults_emitsTextThinkingAndDeduplicatedSources() throws Exception {
        CapturedRequest request = startServer(exchange -> respondSse(exchange, searchResultStream()));
        var subject = subject(request.baseUrl());
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        List<WebSearchSource> sources = new ArrayList<>();
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger clears = new AtomicInteger();

        subject.streamCompletion(
                runtime(),
                List.of(Message.system("system"), Message.user("latest")),
                ReasoningLevel.LOW,
                nativeSearch(),
                text::append,
                thinking::append,
                ignored -> {
                },
                ignored -> {
                },
                sources::add,
                () -> false,
                ignored -> registrations.incrementAndGet(),
                clears::incrementAndGet
        );

        assertThat(text).hasToString("Answer");
        assertThat(thinking).hasToString("Thought");
        assertThat(sources).containsExactly(
                new WebSearchSource("First Source", "https://Example.test/source#one"),
                new WebSearchSource("example.test", "https://example.test/other")
        );
        assertThat(request.awaitBody()).contains("\"model\":\"deepseek-v4-pro\"")
                .contains("\"max_tokens\":384000")
                .contains("\"type\":\"web_search_20250305\"")
                .contains("\"effort\":\"low\"")
                .contains("\"system\"");
        assertThat(request.apiKeyValue()).isEqualTo("test-key");
        assertThat(request.authorizationValue()).isNull();
        assertThat(registrations).hasValue(1);
        assertThat(clears).hasValue(1);
    }

    @Test
    @DisplayName("Disabled reasoning is explicit and a successful response may return no sources")
    void streamCompletion_whenThinkingOffAndNoSearchResults_sendsDisabledThinkingAndNoSources() throws Exception {
        CapturedRequest request = startServer(exchange -> respondSse(exchange, textOnlyStream()));
        var subject = subject(request.baseUrl());
        List<WebSearchSource> sources = new ArrayList<>();

        subject.streamCompletion(
                runtime(),
                List.of(Message.user("latest")),
                ReasoningLevel.OFF,
                nativeSearch(),
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                sources::add,
                () -> false,
                ignored -> {
                },
                () -> {
                }
        );

        assertThat(request.awaitBody()).contains("\"thinking\":{\"type\":\"disabled\"}");
        assertThat(request.awaitBody()).doesNotContain("\"output_config\"");
        assertThat(sources).isEmpty();
    }

    @Test
    @DisplayName("Cancellation immediately after registration closes ownership and suppresses callbacks")
    void streamCompletion_whenCancelledDuringRegistration_clearsOwnershipWithoutCallbacks() throws Exception {
        CapturedRequest request = startServer(exchange -> respondSse(exchange, textOnlyStream()));
        var subject = subject(request.baseUrl());
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger tokens = new AtomicInteger();
        AtomicInteger clears = new AtomicInteger();
        AtomicReference<AutoCloseable> registered = new AtomicReference<>();

        subject.streamCompletion(
                runtime(),
                List.of(Message.user("latest")),
                ReasoningLevel.OFF,
                nativeSearch(),
                ignored -> tokens.incrementAndGet(),
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                cancelled::get,
                stream -> {
                    registered.set(stream);
                    cancelled.set(true);
                    try {
                        stream.close();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                },
                clears::incrementAndGet
        );

        assertThat(registered).doesNotHaveValue(null);
        assertThat(tokens).hasValue(0);
        assertThat(clears).hasValue(1);
    }

    @Test
    @DisplayName("Cancellation immediately before a structured source callback suppresses that source")
    void streamCompletion_whenCancellationChangesDuringFinalSourceCheck_suppressesSourceCallback() throws Exception {
        CapturedRequest request = startServer(exchange -> respondSse(exchange, searchResultStream()));
        var subject = subject(request.baseUrl());
        AtomicInteger sourceCancellationChecks = new AtomicInteger();
        List<WebSearchSource> sources = new ArrayList<>();
        BooleanSupplier cancellation = () -> StackWalker.getInstance().walk(frames -> frames
                .anyMatch(frame -> frame.getMethodName().equals("emitSource")))
                && sourceCancellationChecks.incrementAndGet() >= 2;

        subject.streamCompletion(
                runtime(),
                List.of(Message.user("latest")),
                ReasoningLevel.OFF,
                nativeSearch(),
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                sources::add,
                cancellation,
                ignored -> {
                },
                () -> {
                }
        );

        assertThat(sources).isEmpty();
    }

    @Test
    @DisplayName("DeepSeek streams require a message stop event")
    void streamCompletion_whenStreamEndsAfterPartialOutput_throws() throws Exception {
        CapturedRequest request = startServer(exchange -> respondSse(exchange, """
                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Partial"}}

                """));

        assertThatThrownBy(() -> subject(request.baseUrl()).streamCompletion(
                runtime(),
                List.of(Message.user("question")),
                ReasoningLevel.OFF,
                nativeSearch(),
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                () -> false,
                ignored -> {
                },
                () -> {
                }
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("DeepSeek stream ended before message_stop.");
    }

    @Test
    @DisplayName("DeepSeek message stop events require assistant output")
    void streamCompletion_whenMessageStopsWithoutOutput_throws() throws Exception {
        CapturedRequest request = startServer(exchange -> respondSse(exchange, """
                event: message_stop
                data: {"type":"message_stop"}

                """));

        assertThatThrownBy(() -> subject(request.baseUrl()).streamCompletion(
                runtime(),
                List.of(Message.user("question")),
                ReasoningLevel.OFF,
                nativeSearch(),
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                () -> false,
                ignored -> {
                },
                () -> {
                }
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("DeepSeek completed without assistant output.");
    }

    @Test
    @DisplayName("HTTP failures propagate through the SDK and still clear registered ownership")
    void streamCompletion_whenServerRejectsRequest_throwsWithoutRegisteringStream() throws Exception {
        CapturedRequest request = startServer(exchange -> {
            byte[] body = "unavailable".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        var subject = subject(request.baseUrl());
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger clears = new AtomicInteger();

        assertThatThrownBy(() -> subject.streamCompletion(
                runtime(),
                List.of(Message.user("latest")),
                ReasoningLevel.OFF,
                nativeSearch(),
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                () -> false,
                ignored -> registrations.incrementAndGet(),
                clears::incrementAndGet
        )).isInstanceOf(Exception.class);
        assertThat(registrations).hasValue(0);
        assertThat(clears).hasValue(0);
    }

    private DeepSeekAnthropicWebSearchClient subject(String baseUrl) {
        return new DeepSeekAnthropicWebSearchClient(
                ProviderAttachmentTestSupport.authority(),
                (apiKey, sdkBaseUrl) -> AnthropicOkHttpClient.builder()
                        .apiKey(apiKey)
                        .baseUrl(sdkBaseUrl)
                        .build(),
                baseUrl
        );
    }

    private CapturedRequest startServer(ExchangeHandler handler) throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        CountDownLatch requestReceived = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            try {
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                apiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                requestReceived.countDown();
                handler.handle(exchange);
            } catch (Exception e) {
                exchange.close();
                throw new IllegalStateException(e);
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:%d".formatted(server.getAddress().getPort());
        return new CapturedRequest(baseUrl, body, apiKey, authorization, requestReceived);
    }

    private void respondSse(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String searchResultStream() {
        return """
                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Thought"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Answer"}}

                event: content_block_start
                data: {"type":"content_block_start","index":2,"content_block":{"type":"web_search_tool_result","tool_use_id":"tool-1","content":[{"type":"web_search_result","url":"https://Example.test/source#one","title":"First Source","encrypted_content":"secret"},{"type":"web_search_result","url":"https://example.test/source#other","title":"Duplicate","encrypted_content":"secret-two"},{"type":"web_search_result","url":"https://example.test/other","title":"   ","encrypted_content":"secret-three"}]}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
    }

    private String textOnlyStream() {
        return """
                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Answer"}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
    }

    private WebSearchRequestOptions nativeSearch() {
        return new WebSearchRequestOptions(true);
    }

    private ProviderRuntime runtime() {
        var descriptor = new ProviderDescriptor(
                "DeepSeek",
                AuthType.ENV_VAR,
                "DEEPSEEK_API_KEY",
                null,
                "https://api.deepseek.com",
                List.of(),
                ProviderCapabilities.chatAndModels(),
                value -> value
        );
        return new ProviderRuntime(
                descriptor,
                "DEEPSEEK_API_KEY",
                "https://api.deepseek.com",
                "test-key",
                "deepseek-v4-pro"
        );
    }

    private record CapturedRequest(
            String baseUrl,
            AtomicReference<String> body,
            AtomicReference<String> apiKey,
            AtomicReference<String> authorization,
            CountDownLatch requestReceived
    ) {
        String awaitBody() throws InterruptedException {
            if (!requestReceived.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Request was not received.");
            }
            return body.get();
        }

        String apiKeyValue() {
            return apiKey.get();
        }

        String authorizationValue() {
            return authorization.get();
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
