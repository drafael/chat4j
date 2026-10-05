package com.github.drafael.chat4j.chat.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentTestSupport;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class AnthropicToolAgentAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @CsvSource(value = {
            "claude-sonnet-4-6 | HIGH | {\"type\":\"adaptive\",\"display\":\"summarized\"} | high | 8192",
            "claude-sonnet-4-6 | OFF | null | null | 4096",
            "claude-sonnet-5-5 | OFF | {\"type\":\"between_tools\"} | low | 4096",
            "claude-opus-5-5 | MAX | {\"type\":\"adaptive\",\"display\":\"summarized\"} | max | 12288",
            "claude-sonnet-4-5 | HIGH | {\"type\":\"enabled\",\"budget_tokens\":4096} | null | 8192",
            "claude-sonnet-4-5 | OFF | null | null | 4096"
    }, delimiter = '|', nullValues = "null")
    @DisplayName("Anthropic Agent turns use selected reasoning and replay signed blocks unchanged with tool results")
    void executeTurn_selectedReasoning_preservesSignedContinuation(String model, ReasoningLevel level, String thinkingJson, String effort, int maxTokens) throws Exception {
        String firstResponse = """
                {"content":[
                  {"type":"thinking","thinking":"trace","signature":"opaque-signature","extra_metadata":{"key":"value"}},
                  {"type":"redacted_thinking","data":"opaque-redacted-data"},
                  {"type":"text","text":""},
                  {"type":"tool_use","id":"toolu_1","name":"read","input":{}}
                ],"stop_reason":"tool_use"}
                """;
        var bodies = new ArrayList<String>();
        var server = createMessagesServer(List.of(firstResponse, "{\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}"), bodies);
        try {
            var subject = new AnthropicToolAgentAdapter(model, "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort()),
                    "test-key", ProviderAttachmentTestSupport.authority());
            var tokens = new ArrayList<String>();
            var thinking = new ArrayList<String>();
            var errors = new ArrayList<Exception>();
            var callbacks = new AgentRunCallbacks(tokens::add, thinking::add, () -> { }, errors::add);
            var request = new AgentRunRequest(List.of(Message.user("read")), level, Path.of("."), emptyList(), () -> false);
            assertThat(subject.executeTurn(request, callbacks).toolInvocations()).hasSize(1);
            assertThat(subject.executeTurn(request.withToolResults(List.of(new ToolInvocationResult("toolu_1", "read", true, "text", ""))), callbacks).completed()).isTrue();
            assertThat(errors).isEmpty();
            assertThat(tokens).containsExactly("done");
            assertThat(thinking).containsExactlyElementsOf(level.enabled() ? List.of("trace") : emptyList());
            assertThat(bodies).hasSize(2);
            for (String body : bodies) {
                JsonNode payload = JSON.readTree(body);
                assertThat(payload.get("thinking")).isEqualTo(thinkingJson == null ? null : JSON.readTree(thinkingJson));
                assertThat(payload.path("output_config").path("effort").asText(null)).isEqualTo(effort);
                assertThat(payload.path("max_tokens").asInt()).isEqualTo(maxTokens);
            }
            JsonNode initial = JSON.readTree(bodies.getFirst());
            JsonNode followUp = JSON.readTree(bodies.get(1));
            assertThat(followUp.path("system")).isEqualTo(initial.path("system"));
            assertThat(followUp.path("tools")).isEqualTo(initial.path("tools"));
            assertThat(followUp.path("messages").get(0)).isEqualTo(initial.path("messages").get(0));
            assertThat(followUp.path("messages").get(1).path("content"))
                    .isEqualTo(JSON.readTree(firstResponse).path("content"));
        } finally {
            server.stop(0);
        }
    }


    @ParameterizedTest
    @ValueSource(strings = {"\"invalid\"", "[]", "42", "true"})
    @DisplayName("Malformed Anthropic tool inputs fail instead of becoming empty arguments")
    void executeTurn_nonObjectToolInput_rejectsToolBatch(String input) throws Exception {
        String response = """
                {"content":[{"type":"tool_use","id":"toolu_1","name":"read","input":%s}]}
                """.formatted(input);
        var bodies = new ArrayList<String>();
        var server = createMessagesServer(List.of(response), bodies);
        try {
            var subject = new AnthropicToolAgentAdapter("claude-sonnet-4-6",
                    "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort()),
                    "test-key", ProviderAttachmentTestSupport.authority());
            var errors = new ArrayList<Exception>();
            var request = new AgentRunRequest(List.of(Message.user("read")), ReasoningLevel.OFF,
                    Path.of("."), emptyList(), () -> false);
            AgentTurnResult result = subject.executeTurn(request,
                    new AgentRunCallbacks(ignored -> { }, ignored -> { }, () -> { }, errors::add));

            assertThat(result.completed()).isFalse();
            assertThat(result.toolInvocations()).isEmpty();
            assertThat(errors).singleElement().satisfies(error ->
                    assertThat(error).hasMessageContaining("tool input must be an object"));
            assertThat(bodies).hasSize(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Native Anthropic payload preserves MCP name, description, and recursive schema")
    void executeTurn_whenMcpToolIsAdvertised_preservesProviderNeutralDefinition() throws Exception {
        AgentToolDefinition definition = mcpDefinition();
        List<String> requestBodies = new ArrayList<>();
        HttpServer server = createMessagesServer(List.of("""
                {
                  "id": "msg_1",
                  "type": "message",
                  "role": "assistant",
                  "content": [{"type": "text", "text": "done"}],
                  "stop_reason": "end_turn"
                }
                """), requestBodies);
        try {
            var subject = new AnthropicToolAgentAdapter(
                    "claude-sonnet-4",
                    "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort()),
                    "key",
                    "",
                    ProviderAttachmentTestSupport.authority(),
                    List.of(definition)
            );

            subject.executeTurn(
                    new AgentRunRequest(
                            List.of(Message.user("use MCP")),
                            ReasoningLevel.OFF,
                            Path.of("."),
                            emptyList(),
                            () -> false
                    ),
                    new AgentRunCallbacks(token -> { }, thinking -> { }, () -> { }, error -> { })
            );

            JsonNode tool = JSON.readTree(requestBodies.getFirst()).path("tools").get(0);
            assertThat(tool.path("name").asText()).isEqualTo(definition.name());
            assertThat(tool.path("description").asText()).isEqualTo(definition.description());
            assertThat(tool.path("input_schema")).isEqualTo(JSON.valueToTree(definition.inputSchema()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Adapter parses tool_use blocks and includes tool_result on next turn")
    void executeTurn_whenModelReturnsToolUse_includesToolResultInNextRequest() throws Exception {
        String firstResponse = """
                {
                  "id": "msg_1",
                  "type": "message",
                  "role": "assistant",
                  "content": [
                    {
                      "type": "tool_use",
                      "id": "toolu_1",
                      "name": "read",
                      "input": {
                        "path": "note.txt"
                      }
                    }
                  ],
                  "stop_reason": "tool_use"
                }
                """;
        String secondResponse = """
                {
                  "id": "msg_2",
                  "type": "message",
                  "role": "assistant",
                  "content": [
                    {
                      "type": "text",
                      "text": "done"
                    }
                  ],
                  "stop_reason": "end_turn"
                }
                """;

        List<String> requestBodies = new ArrayList<>();
        HttpServer server = createMessagesServer(List.of(firstResponse, secondResponse), requestBodies);
        try {
            int port = server.getAddress().getPort();
            AnthropicToolAgentAdapter subject = new AnthropicToolAgentAdapter(
                    "claude-sonnet-4-20250514",
                    "http://127.0.0.1:%d/v1".formatted(port),
                    "test-key",
                    ProviderAttachmentTestSupport.authority()
            );

            AgentTurnResult firstTurn = subject.executeTurn(
                    new AgentRunRequest(List.of(Message.user("read file")), ReasoningLevel.OFF, Path.of("."), emptyList(), () -> false),
                    new AgentRunCallbacks(token -> {
                    }, thinking -> {
                    }, () -> {
                    }, error -> {
                    })
            );

            assertThat(firstTurn.completed()).isFalse();
            assertThat(firstTurn.toolInvocations()).hasSize(1);
            assertThat(firstTurn.toolInvocations().getFirst().name()).isEqualTo("read");

            AgentTurnResult secondTurn = subject.executeTurn(
                    new AgentRunRequest(
                            List.of(Message.user("read file")),
                            ReasoningLevel.OFF,
                            Path.of("."),
                            List.of(new ToolInvocationResult("toolu_1", "read", true, "note content", "")),
                            () -> false
                    ),
                    new AgentRunCallbacks(token -> {
                    }, thinking -> {
                    }, () -> {
                    }, error -> {
                    })
            );

            assertThat(secondTurn.completed()).isTrue();
            assertThat(secondTurn.toolInvocations()).isEmpty();
            assertThat(requestBodies).hasSize(2);
            assertThat(requestBodies.getFirst()).contains("expert workspace assistant operating inside Chat4J Agent Mode");
            assertThat(requestBodies.get(1)).contains("\"type\":\"tool_result\"");
            assertThat(requestBodies.get(1)).contains("\"tool_use_id\":\"toolu_1\"");
            assertThat(requestBodies.get(1)).contains("note content");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Adapter emits assistant text when no tool_use block is present")
    void executeTurn_whenModelReturnsAssistantText_emitsTokenAndCompletes() throws Exception {
        String response = """
                {
                  "id": "msg_1",
                  "type": "message",
                  "role": "assistant",
                  "content": [
                    {
                      "type": "text",
                      "text": "hello from claude"
                    }
                  ],
                  "stop_reason": "end_turn"
                }
                """;

        List<String> requestBodies = new ArrayList<>();
        HttpServer server = createMessagesServer(List.of(response), requestBodies);
        try {
            int port = server.getAddress().getPort();
            AnthropicToolAgentAdapter subject = new AnthropicToolAgentAdapter(
                    "claude-sonnet-4-20250514",
                    "http://127.0.0.1:%d/v1".formatted(port),
                    "test-key",
                    ProviderAttachmentTestSupport.authority()
            );

            List<String> tokens = new ArrayList<>();
            AgentTurnResult turnResult = subject.executeTurn(
                    new AgentRunRequest(List.of(Message.user("ping")), ReasoningLevel.OFF, Path.of("."), emptyList(), () -> false),
                    new AgentRunCallbacks(tokens::add, thinking -> {
                    }, () -> {
                    }, error -> {
                    })
            );

            assertThat(turnResult.completed()).isTrue();
            assertThat(turnResult.toolInvocations()).isEmpty();
            assertThat(tokens).containsExactly("hello from claude");
            assertThat(requestBodies).hasSize(1);
            assertThat(requestBodies.getFirst()).contains("expert workspace assistant operating inside Chat4J Agent Mode");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Copilot Anthropic adapter uses bearer auth and Copilot headers")
    void executeTurn_whenCopilotAdapterUsed_sendsCopilotAuthHeaders() throws Exception {
        String response = """
                {
                  "id": "msg_1",
                  "type": "message",
                  "role": "assistant",
                  "content": [
                    {
                      "type": "tool_use",
                      "id": "toolu_1",
                      "name": "ls",
                      "input": {
                        "path": "."
                      }
                    }
                  ],
                  "stop_reason": "tool_use"
                }
                """;

        AtomicReference<String> authorizationHeader = new AtomicReference<>();
        AtomicReference<String> copilotIntegrationHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            copilotIntegrationHeader.set(exchange.getRequestHeaders().getFirst("Copilot-Integration-Id"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            AnthropicToolAgentAdapter subject = AnthropicToolAgentAdapter.forCopilot(
                    "claude-haiku-4.5",
                    "http://127.0.0.1:%d".formatted(port),
                    "test-token",
                    "",
                    ProviderAttachmentTestSupport.authority()
            );

            AgentTurnResult result = subject.executeTurn(
                    new AgentRunRequest(List.of(Message.user("list files")), ReasoningLevel.OFF, Path.of("."), emptyList(), () -> false),
                    new AgentRunCallbacks(token -> {
                    }, thinking -> {
                    }, () -> {
                    }, error -> {
                    })
            );

            assertThat(result.toolInvocations()).hasSize(1);
            assertThat(authorizationHeader.get()).isEqualTo("Bearer test-token");
            assertThat(copilotIntegrationHeader.get()).isEqualTo("copilot-developer-cli");
        } finally {
            server.stop(0);
        }
    }

    private AgentToolDefinition mcpDefinition() {
        return new AgentToolDefinition(
                "mcp_inventory_lookup",
                "Look up nested inventory data",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string"),
                                "options", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "tags", Map.of(
                                                        "type", "array",
                                                        "items", Map.of("type", "string")
                                                )
                                        )
                                )
                        ),
                        "required", List.of("query")
                ),
                AgentToolSource.MCP
        );
    }

    private HttpServer createMessagesServer(List<String> responses, List<String> requestBodies) throws Exception {
        AtomicInteger index = new AtomicInteger(0);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(body);

            int current = index.getAndIncrement();
            String response = responses.get(Math.min(current, responses.size() - 1));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}
