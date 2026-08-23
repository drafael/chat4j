package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.core.error.AuthenticationException;
import com.github.drafael.chat4j.provider.core.error.ProviderException;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.drafael.chat4j.provider.support.ProviderAttachmentTestSupport.authority;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MistralConversationsWebSearchClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProviderAttachmentSupport attachmentSupport = authority();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Conversations search sends stateless history and streams text thinking and citations")
    void streamCompletion_whenResponseIsSuccessful_preservesRequestAndStructuredOutput() throws Exception {
        var capturedBody = new AtomicReference<String>();
        var capturedAuthorization = new AtomicReference<String>();
        URI endpoint = startServer(200, successfulFixture(), capturedBody, capturedAuthorization);
        var subject = new MistralConversationsWebSearchClient(
                attachmentSupport,
                new MistralSseTransport(HttpClient.newHttpClient()),
                endpoint
        );
        var tokens = new StringBuilder();
        var thinking = new StringBuilder();
        var citations = new ArrayList<CitationRef>();
        var activeStream = new AtomicReference<AutoCloseable>();
        var cleared = new AtomicBoolean();

        subject.streamCompletion(
                runtime("secret-token"),
                List.of(
                        Message.system("Use current evidence."),
                        Message.user("What changed?"),
                        Message.assistant("I will check."),
                        Message.user(List.of(
                                new TextPart("Please search now."),
                                new ImagePart(
                                        new AttachmentRef(
                                                UUID.randomUUID(),
                                                "missing.png",
                                                "chart.png",
                                                "image/png",
                                                32L,
                                                "hash"
                                        ),
                                        4,
                                        8
                                )
                        ))
                ),
                ReasoningLevel.EXTRA_HIGH,
                new WebSearchRequestOptions(true),
                tokens::append,
                thinking::append,
                ignored -> {
                },
                citations::add,
                ignored -> {
                },
                () -> false,
                request -> {
                    AutoCloseable previous = activeStream.getAndSet(request);
                    if (previous != null && previous != request) {
                        try {
                            previous.close();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                },
                () -> cleared.set(true)
        );

        assertThat(tokens).hasToString("One [1] two [1]");
        assertThat(thinking).hasToString("Checking sources");
        assertThat(citations).singleElement().satisfies(citation -> {
            assertThat(citation.number()).isEqualTo(1);
            assertThat(citation.title()).isEqualTo("Example report");
            assertThat(citation.url()).isEqualTo("https://example.com/report");
            assertThat(citation.citedText()).isEqualTo("A current report");
        });
        assertThat(activeStream.get()).isNotNull();
        assertThat(cleared).isTrue();
        assertThat(capturedAuthorization).hasValue("Bearer secret-token");

        JsonNode body = JSON.readTree(capturedBody.get());
        assertThat(body.path("model").asText()).isEqualTo("mistral-small-latest");
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("store").asBoolean()).isFalse();
        assertThat(body.path("instructions").asText()).isEqualTo("Use current evidence.");
        assertThat(body.path("completion_args").path("reasoning_effort").asText()).isEqualTo("high");
        assertThat(body.path("tools").path(0).path("type").asText()).isEqualTo("web_search");
        assertThat(body.path("inputs")).hasSize(3);
        assertThat(body.path("inputs").path(0).path("role").asText()).isEqualTo("user");
        assertThat(body.path("inputs").path(0).path("content").asText()).isEqualTo("What changed?");
        assertThat(body.path("inputs").path(1).path("role").asText()).isEqualTo("assistant");
        assertThat(body.path("inputs").path(2).path("content").asText())
                .isEqualTo("Please search now.\n\n[Image attached: chart.png]");
    }

    @Test
    @DisplayName("System messages after conversation input are rejected instead of being reordered")
    void streamCompletion_whenSystemMessageAppearsAfterUserInput_failsBeforeTransport() {
        var subject = new MistralConversationsWebSearchClient(
                attachmentSupport,
                new MistralSseTransport(HttpClient.newHttpClient()),
                URI.create("http://127.0.0.1:1/v1/conversations")
        );

        assertThatThrownBy(() -> subject.streamCompletion(
                runtime("test-key"),
                List.of(
                        Message.user("First question"),
                        Message.system("Late instruction"),
                        Message.user("Second question")
                ),
                ReasoningLevel.OFF,
                new WebSearchRequestOptions(true),
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
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system messages after conversation input");
    }

    @Test
    @DisplayName("Disabled reasoning sends the Conversations none effort explicitly")
    void streamCompletion_whenReasoningIsOff_sendsNoneEffort() throws Exception {
        var capturedBody = new AtomicReference<String>();
        URI endpoint = startServer(
                200,
                "event: message.output.delta\ndata: {\"type\":\"message.output.delta\",\"content\":\"ok\"}\n\n"
                        + "event: conversation.response.done\ndata: {\"type\":\"conversation.response.done\"}\n\n",
                capturedBody,
                new AtomicReference<>()
        );
        var subject = new MistralConversationsWebSearchClient(
                attachmentSupport,
                new MistralSseTransport(HttpClient.newHttpClient()),
                endpoint
        );

        stream(subject, "test-key");

        JsonNode body = JSON.readTree(capturedBody.get());
        assertThat(body.path("completion_args").path("reasoning_effort").asText()).isEqualTo("none");
    }

    @Test
    @DisplayName("A terminal event completes without waiting for the server to close the stream")
    void streamCompletion_whenTerminalEventIsFlushed_returnsBeforeEndOfStream() throws Exception {
        var terminalFlushed = new CountDownLatch(1);
        var releaseExchange = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/conversations", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                byte[] terminalEvent = ("event: message.output.delta\n"
                        + "data: {\"type\":\"message.output.delta\",\"content\":\"ok\"}\n\n"
                        + "event: conversation.response.done\n"
                        + "data: {\"type\":\"conversation.response.done\"}\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(terminalEvent);
                exchange.getResponseBody().flush();
                terminalFlushed.countDown();
                releaseExchange.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:%d/v1/conversations".formatted(server.getAddress().getPort()));
        var subject = new MistralConversationsWebSearchClient(
                attachmentSupport,
                new MistralSseTransport(HttpClient.newHttpClient()),
                endpoint
        );
        var failure = new AtomicReference<Throwable>();
        Thread requestThread = Thread.ofVirtual().start(() -> {
            try {
                stream(subject, "test-key");
            } catch (Throwable t) {
                failure.set(t);
            }
        });

        try {
            assertThat(terminalFlushed.await(5, TimeUnit.SECONDS)).isTrue();
            requestThread.join(5_000);

            assertThat(requestThread.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
        } finally {
            releaseExchange.countDown();
            requestThread.join(5_000);
        }
    }

    @Test
    @DisplayName("Cancellation after a token suppresses later stream output")
    void streamCompletion_whenCancelledDuringStream_stopsWithoutTerminalFailure() throws Exception {
        URI endpoint = startServer(200, successfulFixture(), new AtomicReference<>(), new AtomicReference<>());
        var subject = new MistralConversationsWebSearchClient(
                attachmentSupport,
                new MistralSseTransport(HttpClient.newHttpClient()),
                endpoint
        );
        var cancelled = new AtomicBoolean();
        var tokens = new StringBuilder();
        var citations = new ArrayList<CitationRef>();

        subject.streamCompletion(
                runtime("test-key"),
                List.of(Message.user("Search")),
                ReasoningLevel.OFF,
                new WebSearchRequestOptions(true),
                token -> {
                    tokens.append(token);
                    cancelled.set(true);
                },
                ignored -> {
                },
                ignored -> {
                },
                citations::add,
                ignored -> {
                },
                cancelled::get,
                ignored -> {
                },
                () -> {
                }
        );

        assertThat(tokens).hasToString("One");
        assertThat(citations).isEmpty();
    }

    @Test
    @DisplayName("Cancellation on a citation marker retains its citation metadata")
    void streamCompletion_whenCitationMarkerCancels_retainsCitationMetadata() throws Exception {
        URI endpoint = startServer(
                200,
                """
                        event: message.output.delta
                        data: {"type":"message.output.delta","content":{"type":"tool_reference","tool":"web_search","title":"Example","url":"https://example.com"}}

                        event: conversation.response.done
                        data: {"type":"conversation.response.done"}

                        """,
                new AtomicReference<>(),
                new AtomicReference<>()
        );
        var subject = new MistralConversationsWebSearchClient(
                attachmentSupport,
                new MistralSseTransport(HttpClient.newHttpClient()),
                endpoint
        );
        var cancelled = new AtomicBoolean();
        var tokens = new StringBuilder();
        var citations = new ArrayList<CitationRef>();

        subject.streamCompletion(
                runtime("test-key"),
                List.of(Message.user("Search")),
                ReasoningLevel.OFF,
                new WebSearchRequestOptions(true),
                token -> {
                    tokens.append(token);
                    cancelled.set(true);
                },
                ignored -> {
                },
                ignored -> {
                },
                citations::add,
                ignored -> {
                },
                cancelled::get,
                ignored -> {
                },
                () -> {
                }
        );

        assertThat(tokens).hasToString(" [1]");
        assertThat(citations).singleElement().satisfies(citation -> assertThat(citation.number()).isEqualTo(1));
    }

    @Test
    @DisplayName("Cancellation while awaiting response headers cancels the request")
    void streamCompletion_whenCancelledBeforeHeaders_returnsPromptly() throws Exception {
        var requestArrived = new CountDownLatch(1);
        var releaseResponse = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/conversations", exchange -> {
            requestArrived.countDown();
            try {
                releaseResponse.await();
                exchange.sendResponseHeaders(204, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:%d/v1/conversations".formatted(server.getAddress().getPort()));
        var subject = new MistralConversationsWebSearchClient(
                attachmentSupport,
                new MistralSseTransport(HttpClient.newHttpClient()),
                endpoint
        );
        var cancelled = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        Thread requestThread = Thread.ofVirtual().start(() -> {
            try {
                subject.streamCompletion(
                        runtime("test-key"),
                        List.of(Message.user("Search")),
                        ReasoningLevel.OFF,
                        new WebSearchRequestOptions(true),
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
                        cancelled::get,
                        ignored -> {
                        },
                        () -> {
                        }
                );
            } catch (Throwable t) {
                failure.set(t);
            }
        });

        try {
            assertThat(requestArrived.await(5, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
            requestThread.join(5_000);

            assertThat(requestThread.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
        } finally {
            releaseResponse.countDown();
            requestThread.join(5_000);
        }
    }

    @Test
    @DisplayName("Cancellation closes a response body that completed concurrently")
    @SuppressWarnings("unchecked")
    void streamCompletion_whenCancelledResponseAlreadyCompleted_closesBody() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        InputStream body = mock(InputStream.class);
        when(response.body()).thenReturn(body);
        doReturn(CompletableFuture.completedFuture(response))
                .when(httpClient)
                .sendAsync(any(), any(HttpResponse.BodyHandler.class));
        var subject = new MistralConversationsWebSearchClient(
                attachmentSupport,
                new MistralSseTransport(httpClient),
                URI.create("https://api.mistral.ai/v1/conversations")
        );
        var cancelled = new AtomicBoolean();

        subject.streamCompletion(
                runtime("test-key"),
                List.of(Message.user("Search")),
                ReasoningLevel.OFF,
                new WebSearchRequestOptions(true),
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
                cancelled::get,
                activeStream -> {
                    try {
                        activeStream.close();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    cancelled.set(true);
                },
                () -> {
                }
        );

        verify(body, atLeastOnce()).close();
    }

    @Test
    @DisplayName("Registration failures cancel an already-started response")
    @SuppressWarnings("unchecked")
    void streamCompletion_whenActiveRequestRegistrationFails_closesCompletedBody() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        InputStream body = mock(InputStream.class);
        when(response.body()).thenReturn(body);
        doReturn(CompletableFuture.completedFuture(response))
                .when(httpClient)
                .sendAsync(any(), any(HttpResponse.BodyHandler.class));
        var subject = new MistralConversationsWebSearchClient(
                attachmentSupport,
                new MistralSseTransport(httpClient),
                URI.create("https://api.mistral.ai/v1/conversations")
        );
        var cleared = new AtomicBoolean();

        assertThatThrownBy(() -> subject.streamCompletion(
                runtime("test-key"),
                List.of(Message.user("Search")),
                ReasoningLevel.OFF,
                new WebSearchRequestOptions(true),
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
                    throw new IllegalStateException("registration failed");
                },
                () -> cleared.set(true)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("registration failed");

        verify(body).close();
        assertThat(cleared).isTrue();
    }

    @Test
    @DisplayName("The standard SSE done sentinel completes a response containing answer output")
    void streamCompletion_whenDoneSentinelArrivesAfterOutput_completesSuccessfully() throws Exception {
        URI endpoint = startServer(
                200,
                "event: message.output.delta\ndata: {\"type\":\"message.output.delta\",\"content\":\"answer\"}\n\n"
                        + "data: [DONE]\n\n",
                new AtomicReference<>(),
                new AtomicReference<>()
        );
        var subject = new MistralConversationsWebSearchClient(
                attachmentSupport,
                new MistralSseTransport(HttpClient.newHttpClient()),
                endpoint
        );

        stream(subject, "test-key");
    }

    @Test
    @DisplayName("A terminal marker without answer output fails instead of returning empty success")
    void streamCompletion_whenTerminalArrivesWithoutOutput_fails() throws Exception {
        URI endpoint = startServer(
                200,
                "event: conversation.response.done\ndata: {\"type\":\"conversation.response.done\"}\n\n",
                new AtomicReference<>(),
                new AtomicReference<>()
        );
        var subject = new MistralConversationsWebSearchClient(
                attachmentSupport,
                new MistralSseTransport(HttpClient.newHttpClient()),
                endpoint
        );

        assertThatThrownBy(() -> stream(subject, "test-key"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("completed without answer output");
    }

    @Test
    @DisplayName("Citation-only terminal responses fail instead of masquerading as an answer")
    void streamCompletion_whenTerminalContainsOnlyCitation_fails() throws Exception {
        URI endpoint = startServer(
                200,
                """
                        event: message.output.delta
                        data: {"type":"message.output.delta","content":{"type":"tool_reference","tool":"web_search","title":"Example","url":"https://example.com"}}

                        event: conversation.response.done
                        data: {"type":"conversation.response.done"}

                        """,
                new AtomicReference<>(),
                new AtomicReference<>()
        );
        var subject = new MistralConversationsWebSearchClient(
                attachmentSupport,
                new MistralSseTransport(HttpClient.newHttpClient()),
                endpoint
        );

        assertThatThrownBy(() -> stream(subject, "test-key"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("completed without answer output");
    }

    @Test
    @DisplayName("A stream without a terminal event fails instead of returning partial success")
    void streamCompletion_whenTerminalEventIsMissing_fails() throws Exception {
        URI endpoint = startServer(
                200,
                "event: message.output.delta\ndata: {\"type\":\"message.output.delta\",\"content\":\"partial\"}\n\n",
                new AtomicReference<>(),
                new AtomicReference<>()
        );
        var subject = new MistralConversationsWebSearchClient(
                attachmentSupport,
                new MistralSseTransport(HttpClient.newHttpClient()),
                endpoint
        );

        assertThatThrownBy(() -> stream(subject, "test-key"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("ended before a completion event");
    }

    @Test
    @DisplayName("HTTP authentication failures hide malformed response bodies")
    void streamCompletion_whenHttpAuthenticationFails_hidesMalformedBody() throws Exception {
        URI endpoint = startServer(
                401,
                "credential secret-token is invalid",
                new AtomicReference<>(),
                new AtomicReference<>()
        );
        var subject = new MistralConversationsWebSearchClient(
                attachmentSupport,
                new MistralSseTransport(HttpClient.newHttpClient()),
                endpoint
        );

        assertThatThrownBy(() -> stream(subject, "secret-token"))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("HTTP 401", "unparseable error response")
                .hasMessageNotContaining("secret-token")
                .hasMessageNotContaining("credential");
    }

    @Test
    @DisplayName("Empty HTTP error bodies retain a useful status diagnostic")
    void streamCompletion_whenHttpErrorBodyIsEmpty_reportsStatus() throws Exception {
        URI endpoint = startServer(
                503,
                "",
                new AtomicReference<>(),
                new AtomicReference<>()
        );
        var subject = new MistralConversationsWebSearchClient(
                attachmentSupport,
                new MistralSseTransport(HttpClient.newHttpClient()),
                endpoint
        );

        assertThatThrownBy(() -> stream(subject, "test-key"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("HTTP 503", "empty error response");
    }

    @Test
    @DisplayName("Structured HTTP errors expose the provider message without raw JSON")
    void streamCompletion_whenHttpRequestIsRejected_extractsProviderMessage() throws Exception {
        URI endpoint = startServer(
                400,
                "{\"object\":\"Error\",\"message\":\"Model codestral-latest currently does not support builtin connectors.\",\"code\":3004}",
                new AtomicReference<>(),
                new AtomicReference<>()
        );
        var subject = new MistralConversationsWebSearchClient(
                attachmentSupport,
                new MistralSseTransport(HttpClient.newHttpClient()),
                endpoint
        );

        assertThatThrownBy(() -> stream(subject, "test-key"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("Model codestral-latest currently does not support builtin connectors.")
                .hasMessageNotContaining("\"object\"");
    }

    @Test
    @DisplayName("Conversation error events terminate the request without a fallback")
    void streamCompletion_whenConversationReturnsError_fails() throws Exception {
        URI endpoint = startServer(
                200,
                "event: conversation.response.error\ndata: {\"type\":\"conversation.response.error\",\"code\":422,\"message\":\"Web search unavailable\"}\n\n",
                new AtomicReference<>(),
                new AtomicReference<>()
        );
        var subject = new MistralConversationsWebSearchClient(
                attachmentSupport,
                new MistralSseTransport(HttpClient.newHttpClient()),
                endpoint
        );

        assertThatThrownBy(() -> stream(subject, "test-key"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("Web search unavailable");
    }

    @Test
    @DisplayName("Empty conversation error fields retain useful defaults")
    void streamCompletion_whenConversationErrorFieldsAreEmpty_usesDefaults() throws Exception {
        URI endpoint = startServer(
                200,
                "event: conversation.response.error\ndata: {\"type\":\"conversation.response.error\",\"code\":\"\",\"message\":\"\"}\n\n",
                new AtomicReference<>(),
                new AtomicReference<>()
        );
        var subject = new MistralConversationsWebSearchClient(
                attachmentSupport,
                new MistralSseTransport(HttpClient.newHttpClient()),
                endpoint
        );

        assertThatThrownBy(() -> stream(subject, "test-key"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("error unknown: Request failed");
    }

    @Test
    @DisplayName("Named terminal events reject non-object JSON payloads")
    void streamCompletion_whenNamedTerminalPayloadIsScalar_fails() throws Exception {
        URI endpoint = startServer(
                200,
                "event: conversation.response.done\ndata: \"invalid\"\n\n",
                new AtomicReference<>(),
                new AtomicReference<>()
        );
        var subject = new MistralConversationsWebSearchClient(
                attachmentSupport,
                new MistralSseTransport(HttpClient.newHttpClient()),
                endpoint
        );

        assertThatThrownBy(() -> stream(subject, "test-key"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("malformed SSE data");
    }

    @Test
    @DisplayName("Malformed SSE data fails with a sanitized protocol diagnostic")
    void streamCompletion_whenSseDataIsMalformed_fails() throws Exception {
        URI endpoint = startServer(
                200,
                "event: message.output.delta\ndata: {not-json}\n\n",
                new AtomicReference<>(),
                new AtomicReference<>()
        );
        var subject = new MistralConversationsWebSearchClient(
                attachmentSupport,
                new MistralSseTransport(HttpClient.newHttpClient()),
                endpoint
        );

        assertThatThrownBy(() -> stream(subject, "test-key"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("malformed SSE data");
    }

    private void stream(MistralConversationsWebSearchClient subject, String apiKey) throws Exception {
        subject.streamCompletion(
                runtime(apiKey),
                List.of(Message.user("Search")),
                ReasoningLevel.OFF,
                new WebSearchRequestOptions(true),
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
        );
    }

    private URI startServer(
            int status,
            String responseBody,
            AtomicReference<String> capturedBody,
            AtomicReference<String> capturedAuthorization
    ) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/conversations", exchange -> respond(
                exchange,
                status,
                responseBody,
                capturedBody,
                capturedAuthorization
        ));
        server.start();
        return URI.create("http://127.0.0.1:%d/v1/conversations".formatted(server.getAddress().getPort()));
    }

    private void respond(
            HttpExchange exchange,
            int status,
            String responseBody,
            AtomicReference<String> capturedBody,
            AtomicReference<String> capturedAuthorization
    ) throws IOException {
        try (exchange) {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(status, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
        }
    }

    private ProviderRuntime runtime(String apiKey) {
        var descriptor = new ProviderDescriptor(
                "Mistral",
                AuthType.ENV_VAR,
                "MISTRAL_API_KEY",
                null,
                "https://api.mistral.ai/v1",
                List.of(),
                ProviderCapabilities.chatAndModels(),
                value -> value
        );
        return new ProviderRuntime(
                descriptor,
                "MISTRAL_API_KEY",
                "https://api.mistral.ai/v1",
                apiKey,
                "mistral-small-latest"
        );
    }

    private String successfulFixture() {
        return """
                event: conversation.response.started
                data: {"type":"conversation.response.started","conversation_id":"conv_test"}

                event: tool.execution.started
                data: {"type":"tool.execution.started","name":"web_search"}

                event: message.output.delta
                data: {"type":"message.output.delta","content":"One"}

                event: message.output.delta
                data: {"type":"message.output.delta","content":{"type":"thinking","thinking":[{"type":"text","text":"Checking sources"}]}}

                event: message.output.delta
                data: {"type":"message.output.delta","content":{"type":"tool_reference","tool":"web_search","title":"Example report","url":"https://example.com/report","description":"A current report"}}

                event: message.output.delta
                data: {"type":"message.output.delta","content":{"type":"text","text":" two"}}

                event: message.output.delta
                data: {"type":"message.output.delta","content":{"type":"tool_reference","tool":"web_search","title":"Duplicate","url":"https://example.com/report"}}

                event: future.event
                data: {"type":"future.event","value":"ignored"}

                event: conversation.response.done
                data: {"type":"conversation.response.done","conversation_id":"conv_test"}

                """;
    }
}
