package com.github.drafael.chat4j.chat.agent;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class AnthropicToolAgentAdapterTest {

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
                    "test-key"
            );

            AgentTurnResult firstTurn = subject.executeTurn(
                    new AgentRunRequest(List.of(Message.user("read file")), ReasoningLevel.OFF, java.nio.file.Path.of("."), emptyList(), () -> false),
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
                            java.nio.file.Path.of("."),
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
                    "test-key"
            );

            List<String> tokens = new ArrayList<>();
            AgentTurnResult turnResult = subject.executeTurn(
                    new AgentRunRequest(List.of(Message.user("ping")), ReasoningLevel.OFF, java.nio.file.Path.of("."), emptyList(), () -> false),
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
