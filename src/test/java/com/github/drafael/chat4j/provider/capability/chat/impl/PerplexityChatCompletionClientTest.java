package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.json.JsonCodec;
import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.PerplexityModelIds;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentTestSupport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PerplexityChatCompletionClientTest {

    private static final JsonCodec JSON = JsonCodec.standard();

    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<CitationRef> citations = new CopyOnWriteArrayList<>();
    private final StringBuilder output = new StringBuilder();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;
    private PerplexityChatCompletionClient subject;
    private Function<Request, String> response = ignored -> completed("answer");
    private int statusCode = 200;
    private String retryAfter = "";
    private final List<Long> requestTimes = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        subject = new PerplexityChatCompletionClient(ProviderAttachmentTestSupport.authority());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                Request request = new Request(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        requestBody.length == 0 ? emptyMap() : JSON.read(new String(requestBody, StandardCharsets.UTF_8), Map.class)
                );
                requests.add(request);
                requestTimes.add(System.nanoTime());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes = response.apply(request).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                if (!retryAfter.isEmpty()) {
                    exchange.getResponseHeaders().add("Retry-After", retryAfter);
                }
                exchange.sendResponseHeaders(statusCode, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @ParameterizedTest
    @CsvSource({
            "sonar, fast", "sonar-pro, fast", "sonar-reasoning-pro, low", "sonar-deep-research, high",
            "fast, fast", "low, low", "medium, medium", "high, high", " PERPLEXITY/SONAR-PRO , fast"
    })
    @DisplayName("Saved Sonar selections and research efforts use the Agent API without changing conversation history")
    void streamCompletion_supportedSelection_postsAgentEffortAndInput(String selection, String effort) throws Exception {
        subject.streamCompletion(
                runtime(baseUrl(), selection),
                List.of(Message.system("system instruction"), Message.user("question"), Message.assistant("previous answer"), Message.user("follow-up")),
                ReasoningLevel.MEDIUM,
                output::append,
                ignored -> {
                },
                () -> false,
                ignored -> {
                },
                () -> {
                }
        );

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/v1/agent");
            assertThat(request.body()).containsEntry("effort", effort).containsEntry("stream", false)
                    .containsEntry("store", !effort.equals("fast"));
            assertThat(request.body()).doesNotContainKeys("model", "messages", "request", "preset", "reasoning");
            if (effort.equals("fast")) {
                assertThat(request.body()).doesNotContainKey("background");
            } else {
                assertThat(request.body()).containsEntry("background", true);
            }
            assertThat(request.body().get("input")).isEqualTo(List.of(
                    Map.of("type", "message", "role", "system", "content", "system instruction"),
                    Map.of("type", "message", "role", "user", "content", "question"),
                    Map.of("type", "message", "role", "assistant", "content", "previous answer"),
                    Map.of("type", "message", "role", "user", "content", "follow-up")
            ));
        });
        assertThat(authorization).hasValue("Bearer test-key");
        assertThat(output).hasToString("answer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "/v1", "/v1/"})
    @DisplayName("Perplexity base URLs with or without a version suffix resolve to one Agent API path")
    void streamCompletion_supportedBaseUrl_postsWithoutDuplicatingVersion(String suffix) throws Exception {
        complete(runtime(baseUrl() + suffix, "fast"), () -> false, () -> {
        });

        assertThat(requests).extracting(Request::path).containsExactly("/v1/agent");
    }

    @Test
    @DisplayName("Research polls queued and in-progress responses before reading the final typed output")
    void streamCompletion_backgroundResearch_pollsUntilCompleted() throws Exception {
        response = request -> switch (requests.size()) {
            case 1 -> "{\"id\":\"resp-123\",\"status\":\"queued\"}";
            case 2 -> "{\"id\":\"resp-123\",\"status\":\"in_progress\",\"paused\":{\"reason\":\"token_limit\"}}";
            default -> completed("deep answer");
        };

        complete(runtime(baseUrl(), "high"), () -> false, () -> {
        });

        assertThat(requests).extracting(Request::method).containsExactly("POST", "GET", "GET");
        assertThat(requests).extracting(Request::path).containsExactly("/v1/agent", "/v1/agent/resp-123", "/v1/agent/resp-123");
        assertThat(output).hasToString("deep answer");
        assertThat(requestTimes.get(1) - requestTimes.getFirst()).isGreaterThanOrEqualTo(Duration.ofSeconds(2).toNanos());
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "cancelled", "incomplete"})
    @DisplayName("Terminal unsuccessful responses are reported rather than polled forever or emitted as successful answers")
    void streamCompletion_unsuccessfulTerminalStatus_reportsFailure(String status) {
        response = request -> requests.size() == 1
                ? "{\"id\":\"resp-123\",\"status\":\"queued\"}"
                : "{\"status\":\"%s\",\"error\":{\"message\":\"terminal detail\"},\"output\":[]}".formatted(status);

        assertThatThrownBy(() -> complete(runtime(baseUrl(), "sonar-deep-research"), () -> false, () -> {
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining(status).hasMessageContaining("terminal detail");
        assertThat(requests).hasSize(2);
        assertThat(output).isEmpty();
        assertThat(citations).isEmpty();
    }

    @Test
    @DisplayName("Throttled polling honors Retry-After and retrieves the same response without resubmission")
    void streamCompletion_pollRateLimited_retriesGetWithoutResubmitting() throws Exception {
        response = request -> {
            if (requests.size() == 1) {
                return "{\"id\":\"resp-owned\",\"status\":\"queued\"}";
            }
            if (requests.size() == 2) {
                statusCode = 429;
                retryAfter = "3";
                return "{\"error\":{\"type\":\"request_rate_limit_exceeded\"}}";
            }
            statusCode = 200;
            return completed("answer");
        };

        complete(runtime(baseUrl(), "low"), () -> false, () -> {
        });

        assertThat(requests).extracting(Request::method).containsExactly("POST", "GET", "GET");
        assertThat(requests).extracting(Request::path).containsExactly("/v1/agent", "/v1/agent/resp-owned", "/v1/agent/resp-owned");
        assertThat(requestTimes.get(2) - requestTimes.get(1)).isGreaterThanOrEqualTo(Duration.ofSeconds(3).toNanos());
        assertThat(output).hasToString("answer");
    }

    @Test
    @DisplayName("A completed response without text or sources is not silently accepted")
    void streamCompletion_completedWithoutOutput_reportsFailure() {
        response = ignored -> "{\"status\":\"completed\",\"output\":[]}";

        assertThatThrownBy(() -> complete(runtime(baseUrl(), "fast"), () -> false, () -> {
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("without assistant output");
    }

    @Test
    @DisplayName("Typed response parsing emits only assistant output text, not reasoning or tool output")
    void streamCompletion_typedOutput_readsAllAssistantTextParts() throws Exception {
        response = ignored -> """
                {"status":"completed","output":[
                  {"type":"reasoning","content":[{"type":"output_text","text":"private reasoning"}]},
                  {"type":"message","role":"user","content":[{"type":"output_text","text":"echoed prompt"}]},
                  {"type":"function_call","arguments":"tool arguments"},
                  {"type":"message","role":"assistant","content":[
                    {"type":"output_text","text":"first paragraph"},
                    {"type":"output_text","text":"second paragraph"}
                  ]}
                ]}
                """;

        complete(runtime(baseUrl(), "fast"), () -> false, () -> {
        });

        assertThat(output).hasToString("first paragraph\nsecond paragraph");
    }

    @Test
    @DisplayName("Source IDs remain correct across reordered results, duplicate URLs, and multiple search steps")
    void streamCompletion_nonSequentialDuplicateSources_linksByIdThenDeduplicates() throws Exception {
        response = ignored -> """
                {"status":"completed","output":[
                  {"type":"search_results","results":[
                    {"id":7,"source":"web","title":"Second","url":"https://example.test/second/","snippet":"second excerpt"},
                    {"id":2,"source":"web","title":"First","url":"https://example.test/first","content":"first excerpt"},
                    {"id":9,"source":"web","title":"Duplicate","url":"https://example.test/second#"}
                  ]},
                  {"type":"search_results","results":[
                    {"id":12,"source":"web","url":"https://example.test/third"}
                  ]},
                  {"type":"message","role":"assistant","content":[{"type":"output_text",
                    "text":"answer [web:2] and [7][9] then [12], unknown [8] and [999999999999999999999]"}]}
                ]}
                """;

        complete(runtime(baseUrl(), "fast"), () -> false, () -> {
        });

        assertThat(output.toString()).contains("answer [2](<https://example.test/first>) and [1](<https://example.test/second>) then [3](<https://example.test/third>)");
        assertThat(output.toString()).contains("unknown [8] and [999999999999999999999]");
        assertThat(citations).extracting(CitationRef::number).containsExactly(1, 2, 3);
        assertThat(citations).extracting(CitationRef::kind).containsOnly(CitationKind.WEB);
        assertThat(citations).extracting(CitationRef::url).containsExactly("https://example.test/second", "https://example.test/first", "https://example.test/third");
        assertThat(citations).extracting(CitationRef::citedText).containsExactly("second excerpt", "first excerpt", "");
        assertThat(output.toString()).contains("1. [Second](<https://example.test/second>)", "2. [First](<https://example.test/first>)", "3. <https://example.test/third>");
    }

    @Test
    @DisplayName("Source-typed citations do not collide with numeric web citations")
    void streamCompletion_sourceTypesShareId_keepsTheirLinksDistinct() throws Exception {
        response = ignored -> """
                {"status":"completed","output":[
                  {"type":"search_results","results":[
                    {"id":1,"source":"web","url":"https://example.test/web"},
                    {"id":1,"source":"finance","url":"https://example.test/finance"},
                    {"id":2,"source":"web","url":"javascript:alert(1)"}
                  ]},
                  {"type":"message","role":"assistant","content":[{"type":"output_text","text":"[1] and [finance:1], invalid [web:2]"}]}
                ]}
                """;

        complete(runtime(baseUrl(), "fast"), () -> false, () -> {
        });

        assertThat(output.toString()).contains("[1](<https://example.test/web>) and [2](<https://example.test/finance>), invalid [web:2]");
        assertThat(citations).hasSize(2);
    }

    @Test
    @DisplayName("Sources are still available when the answer omits citation markers or answer text")
    void streamCompletion_sourcesWithoutAnswer_emitsSources() throws Exception {
        response = ignored -> """
                {"status":"completed","output":[{"type":"search_results","results":[
                  {"id":3,"title":"Only source","url":"https://example.test/source"}
                ]}]}
                """;

        complete(runtime(baseUrl(), "fast"), () -> false, () -> {
        });

        assertThat(output.toString()).contains("[1](<https://example.test/source>)", "Sources:", "1. [Only source]");
        assertThat(citations).hasSize(1);
    }

    @Test
    @DisplayName("Optional structured callbacks may be omitted")
    void streamCompletion_nullStructuredCallbacks_completes() throws Exception {
        subject.streamCompletion(
                runtime(baseUrl(), "fast"), List.of(Message.user("question")), ReasoningLevel.OFF,
                WebSearchRequestOptions.disabled(), output::append, null, null, null, () -> false,
                ignored -> {
                }, () -> {
                }
        );

        assertThat(output).hasToString("answer");
    }

    @Test
    @DisplayName("Pre-cancelled requests never contact the provider")
    void streamCompletion_alreadyCancelled_sendsNothing() throws Exception {
        complete(runtime(baseUrl(), "high"), () -> true, () -> {
        });

        assertThat(requests).isEmpty();
        assertThat(output).isEmpty();
    }

    @Test
    @DisplayName("Cancellation after submission cancels the exact background response server-side")
    void streamCompletion_cancelledAfterSubmission_postsCancelAndSuppressesOutput() {
        var cancelled = new AtomicBoolean();
        response = request -> request.path().endsWith("/cancel")
                ? "{\"status\":\"cancelling\"}"
                : "{\"id\":\"resp-owned\",\"status\":\"queued\"}";

        assertThatThrownBy(() -> complete(runtime(baseUrl(), "high"), cancelled::get, () -> cancelled.set(true)))
                .isInstanceOf(InterruptedException.class);

        assertThat(requests).extracting(Request::path).containsExactly("/v1/agent", "/v1/agent/resp-owned/cancel");
        assertThat(requests).extracting(Request::method).containsExactly("POST", "POST");
        assertThat(output).isEmpty();
        assertThat(citations).isEmpty();
    }

    @Test
    @DisplayName("Cancellation interrupts an active poll and still cancels its owned response server-side")
    void streamCompletion_cancelledDuringPoll_closesPollAndCancelsRun() throws Exception {
        var polling = new CountDownLatch(1);
        var releasePoll = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        response = request -> {
            if (request.method().equals("GET")) {
                polling.countDown();
                try {
                    releasePoll.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return request.path().endsWith("/cancel")
                    ? "{\"status\":\"cancelling\"}"
                    : "{\"id\":\"resp-owned\",\"status\":\"queued\"}";
        };
        Thread worker = Thread.startVirtualThread(() -> {
            try {
                complete(runtime(baseUrl(), "high"), cancelled::get, () -> {
                });
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        try {
            assertThat(polling.await(5, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
            worker.interrupt();
            releasePoll.countDown();

            assertThat(worker.join(Duration.ofSeconds(10))).isTrue();
            assertThat(failure.get()).isNotNull();
            assertThat(requests).extracting(Request::path).containsExactly("/v1/agent", "/v1/agent/resp-owned", "/v1/agent/resp-owned/cancel");
            assertThat(output).isEmpty();
        } finally {
            cancelled.set(true);
            releasePoll.countDown();
            worker.interrupt();
            worker.join();
        }
    }

    @Test
    @DisplayName("Rate-limited cancellation waits and retries only the cancellation operation")
    void streamCompletion_cancellationRateLimited_honorsRetryAfter() {
        var cancelled = new AtomicBoolean();
        response = request -> {
            if (requests.size() == 2) {
                statusCode = 429;
                retryAfter = "2";
                return "{}";
            }
            statusCode = 200;
            return "{\"id\":\"resp-owned\",\"status\":\"queued\"}";
        };

        assertThatThrownBy(() -> complete(runtime(baseUrl(), "high"), cancelled::get, () -> cancelled.set(true)))
                .isInstanceOf(InterruptedException.class);

        assertThat(requests).extracting(Request::path).containsExactly("/v1/agent", "/v1/agent/resp-owned/cancel", "/v1/agent/resp-owned/cancel");
        assertThat(requestTimes.get(2) - requestTimes.get(1)).isGreaterThanOrEqualTo(Duration.ofSeconds(2).toNanos());
    }

    @Test
    @DisplayName("Worker interruption does not prevent the separate server-side cancellation request")
    void streamCompletion_interruptedAfterSubmission_cancelsAndPreservesInterrupt() {
        response = ignored -> "{\"id\":\"resp-owned\",\"status\":\"in_progress\"}";
        try {
            assertThatThrownBy(() -> complete(runtime(baseUrl(), "low"), () -> false, () -> Thread.currentThread().interrupt()))
                    .isInstanceOf(InterruptedException.class);

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(requests).extracting(Request::path).containsExactly("/v1/agent", "/v1/agent/resp-owned/cancel");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("Cancellation after a terminal response suppresses callbacks without cancelling a finished run")
    void streamCompletion_completedAsCancellationArrives_emitsNothing() throws Exception {
        var cancelled = new AtomicBoolean();

        complete(runtime(baseUrl(), "high"), cancelled::get, () -> cancelled.set(true));

        assertThat(requests).hasSize(1);
        assertThat(output).isEmpty();
        assertThat(citations).isEmpty();
    }

    @Test
    @DisplayName("An unsuccessful poll cancels the abandoned run without masking the original error")
    void streamCompletion_pollFails_cancelsBackgroundRun() {
        response = request -> {
            if (request.method().equals("GET")) {
                statusCode = 503;
                return "{\"error\":{\"message\":\"poll unavailable\"}}";
            }
            statusCode = request.path().endsWith("/cancel") ? 500 : 200;
            return "{\"id\":\"resp-owned\",\"status\":\"queued\"}";
        };

        assertThatThrownBy(() -> complete(runtime(baseUrl(), "high"), () -> false, () -> {
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("poll unavailable");
        assertThat(requests).extracting(Request::path).containsExactly("/v1/agent", "/v1/agent/resp-owned", "/v1/agent/resp-owned/cancel");
    }

    @Test
    @DisplayName("An unrecognized HTTP error does not expose raw provider content")
    void streamCompletion_unrecognizedHttpError_reportsOnlyStatus() {
        statusCode = 500;
        response = ignored -> "private echoed prompt\n\u001b[31msecret";

        assertThatThrownBy(() -> complete(runtime(baseUrl(), "fast"), () -> false, () -> {
        })).isInstanceOf(IllegalStateException.class).hasMessage("Perplexity chat failed: HTTP 500");
    }

    @Test
    @DisplayName("Malformed background submissions fail instead of polling an empty response ID")
    void streamCompletion_backgroundResponseMissingId_failsClearly() {
        response = ignored -> "{\"status\":\"queued\"}";

        assertThatThrownBy(() -> complete(runtime(baseUrl(), "high"), () -> false, () -> {
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("missing its id");
        assertThat(requests).hasSize(1);
    }

    @Test
    @DisplayName("Unsupported selections are rejected before making a provider request")
    void streamCompletion_unknownSelection_failsBeforeHttp() {
        assertThatThrownBy(() -> complete(runtime(baseUrl(), "unknown"), () -> false, () -> {
        })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported Perplexity");
        assertThat(requests).isEmpty();
    }

    private void complete(ProviderRuntime runtime, BooleanSupplier cancelled, Runnable clear) throws Exception {
        subject.streamCompletion(
                runtime, List.of(Message.user("question")), ReasoningLevel.OFF, WebSearchRequestOptions.disabled(),
                output::append, ignored -> {
                }, ignored -> {
                }, citations::add, cancelled, ignored -> {
                }, clear
        );
    }

    private String baseUrl() {
        return "http://127.0.0.1:%d".formatted(server.getAddress().getPort());
    }

    private static String completed(String text) {
        return JSON.writeString(Map.of("status", "completed", "output", List.of(Map.of(
                "type", "message", "role", "assistant", "content", List.of(Map.of("type", "output_text", "text", text))
        ))));
    }

    private ProviderRuntime runtime(String baseUrl, String selection) {
        return new ProviderRuntime(
                new ProviderDescriptor(
                        "Perplexity", AuthType.ENV_VAR, "PERPLEXITY_API_KEY", null, "https://api.perplexity.ai",
                        PerplexityModelIds.AGENT_EFFORTS, ProviderCapabilities.chatAndModels(), value -> value
                ),
                "PERPLEXITY_API_KEY", baseUrl, "test-key", selection
        );
    }

    private record Request(String method, String path, Map<String, Object> body) {
    }
}
