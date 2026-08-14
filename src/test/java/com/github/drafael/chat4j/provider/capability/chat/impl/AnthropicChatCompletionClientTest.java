package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicChatCompletionClientTest {

    @TempDir
    Path tempDir;

    private AnthropicChatCompletionClient subject;

    @BeforeEach
    void setUp() throws Exception {
        subject = new AnthropicChatCompletionClient(
                new ProviderAttachmentSupport(tempDir)
        );
    }

    @Test
    @DisplayName("Every system part is retained as an ordered Anthropic system text block")
    void systemBlocks_whenHistoryContainsMultipleSystemMessages_preservesAllPartsInOrder() {
        var firstAttachment = unavailableAttachment("first.txt");
        var secondAttachment = unavailableAttachment("second.txt");
        List<Message> history = List.of(
                new Message(Role.SYSTEM, List.of(
                        new TextPart("first text"),
                        new FilePart(firstAttachment)
                ), Instant.now()),
                new Message(Role.USER, "question", Instant.now()),
                new Message(Role.SYSTEM, List.of(
                        new FilePart(secondAttachment),
                        new TextPart("last text")
                ), Instant.now())
        );
        AttachmentProjectionPlan plan = AttachmentProjectionPlan.create(
                history,
                newAuthority(),
                AttachmentProjectionPlan.anthropic(true, true),
                () -> false
        );

        var blocks = subject.systemBlocks(plan);

        assertThat(blocks).extracting(block -> block.text()).containsExactly(
                "first text",
                "[File attached: first.txt]",
                "[File attached: second.txt]",
                "last text"
        );
    }

    @Test
    @DisplayName("Thinking budgets leave room for answer tokens at every supported reasoning level")
    void completionTokenLimit_whenReasoningIsEnabled_exceedsThinkingBudget() {
        assertThat(subject.completionTokenLimit(ReasoningLevel.HIGH, true)).isEqualTo(8192);
        assertThat(subject.completionTokenLimit(ReasoningLevel.EXTRA_HIGH, true)).isEqualTo(12288);
        assertThat(subject.completionTokenLimit(ReasoningLevel.EXTRA_HIGH, false)).isEqualTo(4096);
    }

    @Test
    @DisplayName("Anthropic streams require a message stop event")
    void streamCompletion_whenStreamEndsAfterPartialOutput_throws() throws Exception {
        HttpServer server = startServer("""
                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Partial"}}

                """);
        try {
            assertThatThrownBy(() -> streamFrom(server, WebSearchRequestOptions.disabled(), ignored -> {
            }, ignored -> {
            }, () -> false)).hasMessage("Anthropic stream ended before message_stop.");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Anthropic message stop events require assistant output")
    void streamCompletion_whenMessageStopsWithoutOutput_throws() throws Exception {
        HttpServer server = startServer("""
                event: message_stop
                data: {"type":"message_stop"}

                """);
        try {
            assertThatThrownBy(() -> streamFrom(server, WebSearchRequestOptions.disabled(), ignored -> {
            }, ignored -> {
            }, () -> false)).hasMessage("Anthropic completed without assistant output.");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Anthropic citation metadata is delivered before its marker")
    void streamCompletion_whenCitationCallbackCancels_omitsDanglingMarker() throws Exception {
        HttpServer server = startServer("""
                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Answer"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"citations_delta","citation":{"type":"web_search_result_location","cited_text":"Evidence","url":"https://example.com/source","title":"Source","encrypted_index":"encrypted"}}}

                event: message_stop
                data: {"type":"message_stop"}

                """);
        var cancelled = new AtomicBoolean();
        List<String> tokens = new ArrayList<>();
        List<CitationRef> citations = new ArrayList<>();
        try {
            assertThatCode(() -> streamFrom(
                    server,
                    WebSearchRequestOptions.disabled(),
                    tokens::add,
                    citation -> {
                        citations.add(citation);
                        cancelled.set(true);
                    },
                    cancelled::get
            )).doesNotThrowAnyException();
        } finally {
            server.stop(0);
        }

        assertThat(citations).hasSize(1);
        assertThat(tokens).containsExactly("Answer");
    }

    @Test
    @DisplayName("Anthropic native Web Search is rejected for custom endpoints")
    void streamCompletion_whenSearchUsesCustomEndpoint_rejectsBeforeRequest() {
        ProviderRuntime runtime = runtime("http://127.0.0.1:1");

        assertThatThrownBy(() -> subject.streamCompletion(
                runtime,
                List.of(Message.user("question")),
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
                () -> false,
                ignored -> {
                },
                () -> {
                }
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Native Web Search is unavailable for this Anthropic model or endpoint.");
    }

    private HttpServer startServer(String responseBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private void streamFrom(
            HttpServer server,
            WebSearchRequestOptions webSearchOptions,
            Consumer<String> onToken,
            Consumer<CitationRef> onCitation,
            BooleanSupplier isCancelled
    ) throws Exception {
        subject.streamCompletion(
                runtime("http://127.0.0.1:%d".formatted(server.getAddress().getPort())),
                List.of(Message.user("question")),
                ReasoningLevel.OFF,
                webSearchOptions,
                onToken,
                ignored -> {
                },
                ignored -> {
                },
                onCitation,
                isCancelled,
                ignored -> {
                },
                () -> {
                }
        );
    }

    private ProviderRuntime runtime(String baseUrl) {
        var descriptor = new ProviderDescriptor(
                "Anthropic",
                AuthType.ENV_VAR,
                "ANTHROPIC_API_KEY",
                null,
                "https://api.anthropic.com",
                List.of(),
                ProviderCapabilities.chatAndModels(),
                value -> value
        );
        return new ProviderRuntime(descriptor, "ANTHROPIC_API_KEY", baseUrl, "test-key", "claude-sonnet-4-6");
    }

    private AttachmentRef unavailableAttachment(String name) {
        return new AttachmentRef(null, "/unavailable", name, "text/plain", 1L, "sha");
    }

    private ProviderAttachmentSupport newAuthority() {
        try {
            return new ProviderAttachmentSupport(tempDir);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
