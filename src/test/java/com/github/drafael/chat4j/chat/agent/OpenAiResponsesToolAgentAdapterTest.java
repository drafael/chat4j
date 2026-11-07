package com.github.drafael.chat4j.chat.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.drafael.chat4j.mcp.McpInvocationTarget;
import com.github.drafael.chat4j.mcp.McpRunSession;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.core.error.AuthenticationException;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentTestSupport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.file.Files.writeString;
import static java.util.Collections.emptyList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiResponsesToolAgentAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FINAL_RESPONSE = """
            {"status":"completed","output":[{"type":"message","id":"msg_final","role":"assistant",
              "status":"completed","phase":"final_answer","content":[{"type":"output_text","text":"done","annotations":[]}]}]}
            """;
    private static final String TOOL_RESPONSE = """
            {"status":"completed","output":[
              {"type":"reasoning","id":"rs_1","encrypted_content":"opaque-private-reasoning",
                "summary":[{"type":"summary_text","text":"checking"}]},
              {"type":"message","id":"msg_comment","role":"assistant","status":"completed","phase":"commentary",
                "created_by":"response-only-provenance","content":[{"type":"output_text","text":"I will read it","annotations":[]}]},
              {"type":"function_call","id":"fc_item_1","call_id":"call_read","name":"read","arguments":"{\\\"path\\\":\\\"note.txt\\\"}","status":"completed"}
            ]}
            """;

    @TempDir
    Path projectRoot;

    private HttpServer server;
    private volatile List<Reply> replies;
    private final AtomicInteger replyIndex = new AtomicInteger();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> tokens = new ArrayList<>();
    private final List<String> thinking = new ArrayList<>();
    private final List<Exception> errors = new ArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicInteger completions = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        replies = List.of(new Reply(200, FINAL_RESPONSE));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                paths.add(exchange.getRequestURI().getPath());
                bodies.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
                authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                Reply reply = replies.get(Math.min(replyIndex.getAndIncrement(), replies.size() - 1));
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), bytes.length);
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
    @ValueSource(strings = {"gpt-6.1-sol", "gpt-6.1-terra", "gpt-6-sol", "gpt-6-luna"})
    @DisplayName("Affected OpenAI models use Responses with flattened non-strict tools and stateless continuation")
    void executeTurn_affectedModel_usesResponses(String model) throws Exception {
        AgentProviderAdapter subject = adapter("OpenAI", model, baseUrl() + "/");

        AgentTurnResult result = subject.executeTurn(request(), callbacks());

        assertThat(errors).isEmpty();
        assertThat(result.completed()).isTrue();
        assertThat(tokens).containsExactly("done");
        assertThat(paths).containsExactly("/v1/responses");
        assertThat(authorizations).containsExactly("Bearer test-key");
        JsonNode body = bodies.getFirst();
        assertThat(body.path("model").asText()).isEqualTo(model);
        assertThat(body.path("store").asBoolean(true)).isFalse();
        assertThat(body.path("stream").asBoolean(true)).isFalse();
        assertThat(body.path("include").get(0).asText()).isEqualTo("reasoning.encrypted_content");
        assertThat(body.has("messages")).isFalse();
        assertThat(body.has("previous_response_id")).isFalse();
        assertThat(body.path("instructions").asText()).contains(projectRoot.toString(), "custom-agent-instruction");
        assertThat(body.path("input").get(0).path("content").asText()).isEqualTo("read note");
        assertThat(body.path("reasoning").path("effort").asText()).isEqualTo("medium");
        for (JsonNode tool : body.path("tools")) {
            assertThat(tool.path("type").asText()).isEqualTo("function");
            assertThat(tool.path("name").asText()).isNotBlank();
            assertThat(tool.path("parameters").path("type").asText()).isEqualTo("object");
            assertThat(tool.path("strict").asBoolean(true)).isFalse();
            assertThat(tool.has("function")).isFalse();
        }
        assertThat(completions).hasValue(0);
    }

    @Test
    @DisplayName("Tool continuation uses call IDs, preserves opaque reasoning and assistant phase, and omits output provenance")
    void executeTurn_toolCall_replaysOpaqueItemsAndCallIds() throws Exception {
        replies = List.of(new Reply(200, TOOL_RESPONSE), new Reply(200, FINAL_RESPONSE));
        AgentProviderAdapter subject = adapter("OpenAI", "gpt-6.1-terra", baseUrl() + "/responses");

        AgentTurnResult first = subject.executeTurn(request(), callbacks());
        assertThat(first.toolInvocations()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("call_read");
            assertThat(call.name()).isEqualTo("read");
            assertThat(call.argumentsJson()).isEqualTo("{\"path\":\"note.txt\"}");
        });
        AgentTurnResult second = subject.executeTurn(request().withToolResults(List.of(
                new ToolInvocationResult("call_read", "read", true, "file contents", "")
        )), callbacks());

        assertThat(errors).isEmpty();
        assertThat(second.completed()).isTrue();
        assertThat(paths).containsExactly("/v1/responses", "/v1/responses");
        assertThat(tokens).containsExactly("done");
        assertThat(thinking).containsExactly("checking");
        JsonNode input = bodies.get(1).path("input");
        assertThat(input).hasSize(5);
        assertThat(input.get(1)).isEqualTo(JSON.readTree(TOOL_RESPONSE).path("output").get(0));
        assertThat(input.get(2).path("phase").asText()).isEqualTo("commentary");
        assertThat(input.get(2).has("created_by")).isFalse();
        assertThat(input.get(3).path("id").asText()).isEqualTo("fc_item_1");
        assertThat(input.get(4).path("type").asText()).isEqualTo("function_call_output");
        assertThat(input.get(4).path("call_id").asText()).isEqualTo("call_read");
        assertThat(input.get(4).path("output").asText()).isEqualTo("file contents");
    }

    @Test
    @DisplayName("Multiple tool rounds retain earlier calls and outputs without duplicating the conversation")
    void executeTurn_multipleRounds_replaysCompleteRunHistory() throws Exception {
        String secondResponse = TOOL_RESPONSE.replace("rs_1", "rs_2").replace("fc_item_1", "fc_item_2")
                .replace("call_read", "call_second");
        replies = List.of(new Reply(200, TOOL_RESPONSE), new Reply(200, secondResponse), new Reply(200, FINAL_RESPONSE));
        AgentProviderAdapter subject = adapter("OpenAI", "gpt-6.1-sol", baseUrl());

        subject.executeTurn(request(), callbacks());
        subject.executeTurn(request().withToolResults(List.of(
                new ToolInvocationResult("call_read", "read", false, "", "file not found")
        )), callbacks());
        AgentTurnResult result = subject.executeTurn(request().withToolResults(List.of(
                new ToolInvocationResult("call_second", "read", true, "found", "")
        )), callbacks());

        assertThat(errors).isEmpty();
        assertThat(result.completed()).isTrue();
        JsonNode input = bodies.get(2).path("input");
        assertThat(input).hasSize(9);
        assertThat(input.get(4).path("output").asText()).isEqualTo("ERROR: file not found");
        assertThat(input.get(8).path("call_id").asText()).isEqualTo("call_second");
        assertThat(input.get(8).path("output").asText()).isEqualTo("found");
    }

    @ParameterizedTest
    @CsvSource({"gpt-6.1-terra, OFF, none", "gpt-6.1-terra, MAX, max", "gpt-6.1-sol, EXTRA_HIGH, xhigh"})
    @DisplayName("Responses preserves explicit Off and high effort selections without Chat Completions fields")
    void executeTurn_reasoningSelection_usesResponsesControls(String model, ReasoningLevel level, String effort) {
        AgentProviderAdapter subject = adapter("OpenAI", model, baseUrl());
        AgentRunRequest request = new AgentRunRequest(request().history(), level, projectRoot, emptyList(), cancelled::get);

        subject.executeTurn(request, callbacks());

        assertThat(errors).isEmpty();
        assertThat(bodies.getFirst().path("reasoning").path("effort").asText()).isEqualTo(effort);
        assertThat(bodies.getFirst().has("reasoning_effort")).isFalse();
        if (level == ReasoningLevel.OFF) {
            assertThat(bodies.getFirst().path("reasoning").has("summary")).isFalse();
        }
    }

    @Test
    @DisplayName("Mandatory-thinking Sol omits an unsupported None effort and still replays reasoning when thinking display is off")
    void executeTurn_mandatoryThinkingWithLegacyOff_preservesOpaqueContinuation() {
        replies = List.of(new Reply(200, TOOL_RESPONSE), new Reply(200, FINAL_RESPONSE));
        AgentProviderAdapter subject = adapter("OpenAI", "gpt-6.1-sol", baseUrl());
        var request = new AgentRunRequest(request().history(), ReasoningLevel.OFF, projectRoot, emptyList(), cancelled::get);

        subject.executeTurn(request, callbacks());
        subject.executeTurn(request.withToolResults(List.of(new ToolInvocationResult("call_read", "read", true, "value", ""))), callbacks());

        assertThat(errors).isEmpty();
        assertThat(thinking).isEmpty();
        assertThat(bodies.getFirst().has("reasoning")).isFalse();
        assertThat(bodies.get(1).path("input").get(1).path("encrypted_content").asText()).isEqualTo("opaque-private-reasoning");
    }

    @Test
    @DisplayName("A completed refusal is delivered as an assistant response instead of an empty-success error")
    void executeTurn_refusal_deliversAssistantResponse() {
        replies = List.of(new Reply(200, """
                {"status":"completed","output":[{"type":"message","role":"assistant",
                  "content":[{"type":"refusal","refusal":"cannot comply"}]}]}
                """));
        AgentProviderAdapter subject = adapter("OpenAI", "gpt-6.1-terra", baseUrl());

        assertThat(subject.executeTurn(request(), callbacks()).completed()).isTrue();

        assertThat(errors).isEmpty();
        assertThat(tokens).containsExactly("cannot comply");
    }

    @Test
    @DisplayName("Results without a preceding function-call batch never leave the application")
    void executeTurn_orphanToolResults_rejectsBeforeHttp() {
        AgentProviderAdapter subject = adapter("OpenAI", "gpt-6.1-terra", baseUrl());

        AgentTurnResult result = subject.executeTurn(request().withToolResults(List.of(
                new ToolInvocationResult("orphan", "read", true, "value", "")
        )), callbacks());

        assertThat(result.completed()).isFalse();
        assertThat(errors).hasSize(1);
        assertThat(paths).isEmpty();
    }

    @Test
    @DisplayName("Cancellation from a thinking callback suppresses tool dispatch and leaves the turn uncommitted")
    void executeTurn_cancelledDuringCallback_suppressesTools() {
        replies = List.of(new Reply(200, TOOL_RESPONSE));
        AgentProviderAdapter subject = adapter("OpenAI", "gpt-6.1-terra", baseUrl());
        var callbacks = new AgentRunCallbacks(tokens::add, ignored -> cancelled.set(true), completions::incrementAndGet, errors::add);

        AgentTurnResult result = subject.executeTurn(request(), callbacks);
        cancelled.set(false);
        assertThat(subject.executeTurn(request(), callbacks()).toolInvocations()).hasSize(1);

        assertThat(result.completed()).isFalse();
        assertThat(result.toolInvocations()).isEmpty();
        assertThat(errors).isEmpty();
        assertThat(tokens).isEmpty();
        assertThat(bodies.get(1).path("input")).isEqualTo(bodies.getFirst().path("input"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"status\":\"incomplete\",\"output\":[]}",
            "{\"status\":\"failed\",\"error\":{\"message\":\"test-key must stay private\"},\"output\":[]}",
            "{\"status\":\"in_progress\",\"output\":[]}",
            "{\"status\":\"completed\",\"output\":[]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"summary\":[]}]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"call_id\":\"a\",\"name\":\"read\",\"arguments\":{}}]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"call_id\":\"\",\"name\":\"read\",\"arguments\":\"{}\"}]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"call_id\":\"a\",\"name\":\"read\",\"arguments\":\"{}\"},{\"type\":\"function_call\",\"call_id\":\"a\",\"name\":\"read\",\"arguments\":\"{}\"}]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"call_id\":\"a\",\"name\":\"read\",\"arguments\":\"{}\",\"status\":\"in_progress\"}]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"shell_call\",\"call_id\":\"a\"}]}",
            "not-json"
    })
    @DisplayName("Unfinished, malformed, or unsupported outputs never dispatch tools or report successful completion")
    void executeTurn_invalidResponse_reportsErrorWithoutTools(String body) {
        replies = List.of(new Reply(200, body));
        AgentProviderAdapter subject = adapter("OpenAI", "gpt-6.1-terra", baseUrl());

        AgentTurnResult result = subject.executeTurn(request(), callbacks());

        assertThat(result.completed()).isFalse();
        assertThat(result.toolInvocations()).isEmpty();
        assertThat(errors).singleElement().satisfies(error -> assertThat(error.getMessage()).doesNotContain("test-key"));
        assertThat(tokens).isEmpty();
        assertThat(thinking).isEmpty();
    }

    @Test
    @DisplayName("An HTTP error is classified and sanitized without falling back to another tool transport")
    void executeTurn_authenticationFailure_reportsSanitizedError() {
        replies = List.of(new Reply(401, "{\"error\":{\"message\":\"invalid test-key\",\"code\":\"invalid_api_key\"}}"));
        AgentProviderAdapter subject = adapter("OpenAI", "gpt-6.1-terra", baseUrl());

        AgentTurnResult result = subject.executeTurn(request(), callbacks());

        assertThat(result.completed()).isFalse();
        assertThat(errors).singleElement().isInstanceOf(AuthenticationException.class)
                .satisfies(error -> assertThat(error.getMessage()).contains("401").doesNotContain("test-key"));
        assertThat(paths).containsExactly("/v1/responses");
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 500})
    @DisplayName("Failed continuation can be retried with the identical call and result history")
    void executeTurn_failedContinuation_preservesPendingResponse(int status) {
        replies = List.of(new Reply(200, TOOL_RESPONSE), new Reply(status, "{\"status\":\"incomplete\"}"), new Reply(200, FINAL_RESPONSE));
        AgentProviderAdapter subject = adapter("OpenAI", "gpt-6.1-terra", baseUrl());
        AgentRunRequest continuation = request().withToolResults(List.of(new ToolInvocationResult("call_read", "read", true, "value", "")));

        subject.executeTurn(request(), callbacks());
        assertThat(subject.executeTurn(continuation, callbacks()).completed()).isFalse();
        assertThat(subject.executeTurn(continuation, callbacks()).completed()).isTrue();

        assertThat(errors).hasSize(1);
        assertThat(bodies.get(2).path("input")).isEqualTo(bodies.get(1).path("input"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "wrong", "duplicate"})
    @DisplayName("Missing, mismatched, or duplicate tool results are rejected locally without consuming pending state")
    void executeTurn_invalidToolResults_preservesPendingResponse(String kind) {
        replies = List.of(new Reply(200, TOOL_RESPONSE), new Reply(200, FINAL_RESPONSE));
        AgentProviderAdapter subject = adapter("OpenAI", "gpt-6.1-terra", baseUrl());
        var result = new ToolInvocationResult("call_read", "read", true, "value", "");
        List<ToolInvocationResult> results = switch (kind) {
            case "wrong" -> List.of(new ToolInvocationResult("other", "read", true, "value", ""));
            case "duplicate" -> List.of(result, result);
            default -> emptyList();
        };

        subject.executeTurn(request(), callbacks());
        assertThat(subject.executeTurn(request().withToolResults(results), callbacks()).completed()).isFalse();
        assertThat(bodies).hasSize(1);
        assertThat(subject.executeTurn(request().withToolResults(List.of(result)), callbacks()).completed()).isTrue();
        assertThat(errors).hasSize(1);
    }

    @Test
    @DisplayName("A pre-cancelled turn performs no HTTP request and emits no callbacks")
    void executeTurn_cancelledBeforeRequest_doesNotSend() {
        cancelled.set(true);
        AgentProviderAdapter subject = adapter("OpenAI", "gpt-6.1-terra", baseUrl());

        AgentTurnResult result = subject.executeTurn(request(), callbacks());

        assertThat(result.completed()).isFalse();
        assertThat(paths).isEmpty();
        assertThat(errors).isEmpty();
        assertThat(tokens).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    @DisplayName("Cancellation and interruption close HTTP requests awaiting headers or body without reporting errors")
    void executeTurn_cancelledInFlight_stopsPromptly(boolean headersSent, boolean interrupt) throws Exception {
        var received = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server.createContext("/v1/responses", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                if (headersSent) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write("{\"status\":".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                }
                received.countDown();
                release.await(5, SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        AgentProviderAdapter subject = adapter("OpenAI", "gpt-6.1-terra", baseUrl());
        var interrupted = new AtomicBoolean();
        Thread worker = Thread.ofVirtual().start(() -> {
            subject.executeTurn(request(), callbacks());
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        try {
            assertThat(received.await(5, SECONDS)).isTrue();
            if (interrupt) {
                worker.interrupt();
            } else {
                cancelled.set(true);
            }
            worker.join(5_000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(interrupted.get()).isEqualTo(interrupt);
            assertThat(errors).isEmpty();
            assertThat(tokens).isEmpty();
        } finally {
            cancelled.set(true);
            release.countDown();
            worker.interrupt();
            worker.join(5_000);
        }
    }

    @ParameterizedTest
    @CsvSource({"ALLOW_ONCE, true, true", "ALLOW_ONCE, true, false", "DENY, false, false"})
    @DisplayName("The real Responses tool loop handles local reads and approved or denied MCP tools with the original call IDs")
    void streamCompletion_localAndMcpTools_preservesApprovalAndResults(McpApprovalDecision decision, boolean allowed, boolean toolSuccess) throws Exception {
        writeString(projectRoot.resolve("note.txt"), "local-text");
        ArrayNode output = ((ArrayNode) JSON.readTree(TOOL_RESPONSE).path("output")).deepCopy();
        output.addObject().put("type", "function_call").put("id", "fc_mcp_item").put("call_id", "call_mcp")
                .put("name", "mcp_server_echo_deadbeef").put("arguments", "{\"value\":\"hello\"}");
        replies = List.of(new Reply(200, JSON.createObjectNode().put("status", "completed").set("output", output).toString()),
                new Reply(200, FINAL_RESPONSE));
        McpRunSession session = mock(McpRunSession.class);
        var definition = new AgentToolDefinition("mcp_server_echo_deadbeef", "Echo",
                Map.of("type", "object", "properties", Map.of("value", Map.of("type", "string"))), AgentToolSource.MCP);
        when(session.tools()).thenReturn(List.of(definition));
        when(session.hasTools()).thenReturn(true);
        when(session.handles(definition.name())).thenReturn(true);
        when(session.target(definition.name())).thenReturn(new McpInvocationTarget("Server", "echo", false));
        when(session.redactForDisplay(any(), any())).thenReturn("{\"value\":\"hello\"}");
        when(session.invoke(any(), any(), any(), any(), any())).thenReturn(
                new ToolInvocationResult("call_mcp", definition.name(), toolSuccess, "mcp-text", "MCP server failed"));
        var subject = new AgentOrchestrator(new AgentProviderAdapterFactory(ProviderAttachmentTestSupport.authority()),
                new LocalToolRuntime(), ignored -> session, (approval, cancellation) -> decision);

        subject.streamCompletion("OpenAI", "gpt-6.1-terra", baseUrl(), "test-key", "",
                mock(ProviderService.class), request(), callbacks());

        assertThat(errors).isEmpty();
        assertThat(tokens).containsExactly("done");
        assertThat(completions).hasValue(1);
        assertThat(bodies).hasSize(2);
        JsonNode input = bodies.get(1).path("input");
        assertThat(input.get(5).path("call_id").asText()).isEqualTo("call_read");
        assertThat(input.get(5).path("output").asText()).contains("local-text");
        assertThat(input.get(6).path("call_id").asText()).isEqualTo("call_mcp");
        String expected = allowed
                ? (toolSuccess ? "mcp-text" : "ERROR: MCP server failed")
                : "ERROR: User denied the MCP tool call.";
        assertThat(input.get(6).path("output").asText()).isEqualTo(expected);
        assertThat(bodies.getFirst().path("tools")).anySatisfy(tool -> {
            assertThat(tool.path("name").asText()).isEqualTo(definition.name());
            assertThat(tool.path("parameters")).isEqualTo(JSON.valueToTree(definition.inputSchema()));
            assertThat(tool.path("strict").asBoolean(true)).isFalse();
        });
        if (allowed) {
            verify(session).invoke(any(), any(), any(), any(), any());
        } else {
            verify(session, never()).invoke(any(), any(), any(), any(), any());
        }
        verify(session).close();
    }

    private AgentProviderAdapter adapter(String provider, String model, String endpoint) {
        return new AgentProviderAdapterFactory(ProviderAttachmentTestSupport.authority()).create(
                provider, model, endpoint, "test-key", mock(ProviderService.class), "custom-agent-instruction");
    }

    private String baseUrl() {
        return "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort());
    }

    private AgentRunRequest request() {
        return new AgentRunRequest(List.of(Message.user("read note")), ReasoningLevel.MEDIUM, projectRoot, emptyList(), cancelled::get);
    }

    private AgentRunCallbacks callbacks() {
        return new AgentRunCallbacks(tokens::add, thinking::add, completions::incrementAndGet, errors::add);
    }

    private record Reply(int status, String body) {
    }
}
