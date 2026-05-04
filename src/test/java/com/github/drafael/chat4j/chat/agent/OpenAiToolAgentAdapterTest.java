package com.github.drafael.chat4j.chat.agent;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class OpenAiToolAgentAdapterTest {

    @Test
    @DisplayName("Adapter parses tool calls and includes tool results on next turn")
    void executeTurn_whenModelReturnsToolCalls_includesToolResultInNextRequest() throws Exception {
        String firstResponse = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "",
                        "tool_calls": [
                          {
                            "id": "call_1",
                            "type": "function",
                            "function": {
                              "name": "read",
                              "arguments": "{\\\"path\\\":\\\"note.txt\\\"}"
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
                """;
        String secondResponse = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "done"
                      }
                    }
                  ]
                }
                """;

        List<String> requestBodies = new ArrayList<>();
        HttpServer server = createChatCompletionsServer(List.of(firstResponse, secondResponse), requestBodies, List.of(200, 200));
        try {
            int port = server.getAddress().getPort();
            OpenAiToolAgentAdapter subject = new OpenAiToolAgentAdapter(
                    "OpenAI",
                    "gpt-5-mini",
                    "http://127.0.0.1:%d/v1".formatted(port),
                    "test-key"
            );

            AgentTurnResult firstTurn = subject.executeTurn(
                    new AgentRunRequest(List.of(Message.user("read note")), ReasoningLevel.OFF, java.nio.file.Path.of("."), emptyList(), () -> false),
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
                            List.of(Message.user("read note")),
                            ReasoningLevel.OFF,
                            java.nio.file.Path.of("."),
                            List.of(new ToolInvocationResult("call_1", "read", true, "note content", "")),
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
            assertThat(requestBodies.get(1)).contains("\"role\":\"tool\"");
            assertThat(requestBodies.get(1)).contains("\"tool_call_id\":\"call_1\"");
            assertThat(requestBodies.get(1)).contains("note content");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Adapter passes DeepSeek reasoning content back with tool results")
    void executeTurn_whenDeepSeekToolCallIncludesReasoningContent_preservesReasoningContent() throws Exception {
        String firstResponse = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "",
                        "reasoning_content": "Need to list the folder first.",
                        "tool_calls": [
                          {
                            "id": "call_1",
                            "type": "function",
                            "function": {
                              "name": "ls",
                              "arguments": "{\\\"path\\\":\\\".\\\"}"
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
                """;
        String secondResponse = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "done"
                      }
                    }
                  ]
                }
                """;

        List<String> requestBodies = new ArrayList<>();
        HttpServer server = createChatCompletionsServer(List.of(firstResponse, secondResponse), requestBodies, List.of(200, 200));
        try {
            int port = server.getAddress().getPort();
            OpenAiToolAgentAdapter subject = new OpenAiToolAgentAdapter(
                    "DeepSeek",
                    "deepseek-v4-pro",
                    "http://127.0.0.1:%d/v1".formatted(port),
                    "test-key"
            );

            List<String> thinkingTokens = new ArrayList<>();
            AgentTurnResult firstTurn = subject.executeTurn(
                    new AgentRunRequest(List.of(Message.user("explore folder")), ReasoningLevel.HIGH, java.nio.file.Path.of("."), emptyList(), () -> false),
                    new AgentRunCallbacks(token -> {
                    }, thinkingTokens::add, () -> {
                    }, error -> {
                    })
            );

            assertThat(firstTurn.completed()).isFalse();
            assertThat(firstTurn.toolInvocations()).hasSize(1);
            assertThat(thinkingTokens).containsExactly("Need to list the folder first.");

            AgentTurnResult secondTurn = subject.executeTurn(
                    new AgentRunRequest(
                            List.of(Message.user("explore folder")),
                            ReasoningLevel.HIGH,
                            java.nio.file.Path.of("."),
                            List.of(new ToolInvocationResult("call_1", "ls", true, "note.txt", "")),
                            () -> false
                    ),
                    new AgentRunCallbacks(token -> {
                    }, thinking -> {
                    }, () -> {
                    }, error -> {
                    })
            );

            assertThat(secondTurn.completed()).isTrue();
            assertThat(requestBodies).hasSize(2);
            assertThat(requestBodies.get(1)).contains("\"reasoning_content\":\"Need to list the folder first.\"");
            assertThat(requestBodies.get(1)).contains("\"tool_call_id\":\"call_1\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Adapter emits assistant text when completion has no tool calls")
    void executeTurn_whenModelReturnsAssistantText_emitsTokenAndCompletes() throws Exception {
        String response = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "hello from assistant"
                      }
                    }
                  ]
                }
                """;

        List<String> requestBodies = new ArrayList<>();
        HttpServer server = createChatCompletionsServer(List.of(response), requestBodies, List.of(200));
        try {
            int port = server.getAddress().getPort();
            OpenAiToolAgentAdapter subject = new OpenAiToolAgentAdapter(
                    "OpenAI",
                    "gpt-5-mini",
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
            assertThat(tokens).containsExactly("hello from assistant");
            assertThat(requestBodies).hasSize(1);
            assertThat(requestBodies.getFirst()).contains("expert workspace assistant operating inside Chat4J Agent Mode");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Adapter includes configured prompt addendum in system prompt")
    void executeTurn_whenPromptAddendumProvided_includesAddendumInRequest() throws Exception {
        String response = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "ok"
                      }
                    }
                  ]
                }
                """;

        List<String> requestBodies = new ArrayList<>();
        HttpServer server = createChatCompletionsServer(List.of(response), requestBodies, List.of(200));
        try {
            int port = server.getAddress().getPort();
            OpenAiToolAgentAdapter subject = new OpenAiToolAgentAdapter(
                    "OpenAI",
                    "gpt-5-mini",
                    "http://127.0.0.1:%d/v1".formatted(port),
                    "test-key",
                    "Always mention build files."
            );

            subject.executeTurn(
                    new AgentRunRequest(List.of(Message.user("ping")), ReasoningLevel.OFF, java.nio.file.Path.of("."), emptyList(), () -> false),
                    new AgentRunCallbacks(token -> {
                    }, thinking -> {
                    }, () -> {
                    }, error -> {
                    })
            );

            assertThat(requestBodies).hasSize(1);
            assertThat(requestBodies.getFirst()).contains("Additional instructions");
            assertThat(requestBodies.getFirst()).contains("Always mention build files.");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Tool request timeout is consistent across local and remote providers")
    void resolveRequestTimeout_whenAnyProvider_usesDefaultTimeout() {
        Duration lmStudioTimeout = OpenAiToolAgentAdapter.resolveRequestTimeout("LM Studio", "http://127.0.0.1:1234/v1");
        Duration localhostTimeout = OpenAiToolAgentAdapter.resolveRequestTimeout("Custom", "http://localhost:8080/v1");
        Duration remoteTimeout = OpenAiToolAgentAdapter.resolveRequestTimeout("OpenAI", "https://api.openai.com/v1");

        assertThat(lmStudioTimeout).isEqualTo(Duration.ofSeconds(60));
        assertThat(localhostTimeout).isEqualTo(Duration.ofSeconds(60));
        assertThat(remoteTimeout).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("Adapter returns concise insufficient quota error for HTTP 429")
    void executeTurn_whenQuotaExceeded_returnsConciseError() throws Exception {
        String quotaResponse = """
                {
                  "error": {
                    "message": "You exceeded your current quota, please check your plan and billing details.",
                    "type": "insufficient_quota",
                    "code": "insufficient_quota"
                  }
                }
                """;

        List<String> requestBodies = new ArrayList<>();
        HttpServer server = createChatCompletionsServer(List.of(quotaResponse), requestBodies, List.of(429));
        try {
            int port = server.getAddress().getPort();
            OpenAiToolAgentAdapter subject = new OpenAiToolAgentAdapter(
                    "OpenAI Codex",
                    "gpt-5.5",
                    "http://127.0.0.1:%d/v1".formatted(port),
                    "token"
            );

            List<String> errors = new ArrayList<>();
            AgentTurnResult turnResult = subject.executeTurn(
                    new AgentRunRequest(List.of(Message.user("ping")), ReasoningLevel.OFF, java.nio.file.Path.of("."), emptyList(), () -> false),
                    new AgentRunCallbacks(token -> {
                    }, thinking -> {
                    }, () -> {
                    }, error -> errors.add(error.getMessage()))
            );

            assertThat(turnResult.completed()).isFalse();
            assertThat(turnResult.toolInvocations()).isEmpty();
            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst()).contains("insufficient_quota");
            assertThat(requestBodies.getFirst()).contains("expert workspace assistant operating inside Chat4J Agent Mode");
            assertThat(errors.getFirst()).doesNotContain("\"error\"");
            assertThat(requestBodies).hasSize(1);
        } finally {
            server.stop(0);
        }
    }

    private HttpServer createChatCompletionsServer(
            List<String> responses,
            List<String> requestBodies,
            List<Integer> statusCodes
    ) throws Exception {
        AtomicInteger index = new AtomicInteger(0);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(body);

            int current = index.getAndIncrement();
            String response = responses.get(Math.min(current, responses.size() - 1));
            int statusCode = statusCodes.get(Math.min(current, statusCodes.size() - 1));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}
