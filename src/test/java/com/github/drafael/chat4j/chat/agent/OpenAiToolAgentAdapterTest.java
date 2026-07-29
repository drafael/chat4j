package com.github.drafael.chat4j.chat.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentTestSupport;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class OpenAiToolAgentAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("Native OpenAI payload preserves MCP name, description, and recursive schema")
    void executeTurn_whenMcpToolIsAdvertised_preservesProviderNeutralDefinition() throws Exception {
        AgentToolDefinition definition = mcpDefinition();
        List<String> requestBodies = new ArrayList<>();
        HttpServer server = createChatCompletionsServer(
                List.of("{\"choices\":[{\"message\":{\"content\":\"done\"}}]}"),
                requestBodies,
                List.of(200)
        );
        try {
            var subject = new OpenAiToolAgentAdapter(
                    "OpenAI",
                    "gpt-5",
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

            JsonNode function = JSON.readTree(requestBodies.getFirst()).path("tools").get(0).path("function");
            assertThat(function.path("name").asText()).isEqualTo(definition.name());
            assertThat(function.path("description").asText()).isEqualTo(definition.description());
            assertThat(function.path("parameters")).isEqualTo(JSON.valueToTree(definition.inputSchema()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Direct agent projection uses bounded metadata labels without paths or generated alt text")
    void toChatMessage_whenHistoryContainsGeneratedImage_usesSafeMetadataLabel() throws Exception {
        var attachment = new AttachmentRef(
                UUID.randomUUID(),
                "/private/attachment/uuid",
                "folder\\safe.png",
                "image/png",
                100L,
                "sha"
        );
        Message message = new Message(
                Role.ASSISTANT,
                List.of(new TextPart("result"), new GeneratedImagePart(
                        attachment,
                        null,
                        null,
                        "secret".repeat(100_000)
                )),
                Instant.now()
        );
        List<String> requestBodies = new ArrayList<>();
        HttpServer server = createChatCompletionsServer(
                List.of("{\"choices\":[{\"message\":{\"content\":\"done\"}}]}"),
                requestBodies,
                List.of(200)
        );
        try {
            var subject = new OpenAiToolAgentAdapter(
                    "OpenAI",
                    "gpt-5",
                    "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort()),
                    "key",
                    ProviderAttachmentTestSupport.authority()
            );

            subject.executeTurn(
                    new AgentRunRequest(
                            List.of(message),
                            ReasoningLevel.OFF,
                            Path.of("."),
                            emptyList(),
                            () -> false
                    ),
                    new AgentRunCallbacks(token -> {
                    }, thinking -> {
                    }, () -> {
                    }, error -> {
                    })
            );

            assertThat(requestBodies).singleElement().asString()
                    .contains("result\\n[Generated image: safe.png]")
                    .doesNotContain("/private/attachment/uuid", "secretsecret");
        } finally {
            server.stop(0);
        }
    }

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
                    "test-key",
                    ProviderAttachmentTestSupport.authority()
            );

            AgentTurnResult firstTurn = subject.executeTurn(
                    new AgentRunRequest(List.of(Message.user("read note")), ReasoningLevel.OFF, Path.of("."), emptyList(), () -> false),
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
                            Path.of("."),
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
                    "test-key",
                    ProviderAttachmentTestSupport.authority()
            );

            List<String> thinkingTokens = new ArrayList<>();
            AgentTurnResult firstTurn = subject.executeTurn(
                    new AgentRunRequest(List.of(Message.user("explore folder")), ReasoningLevel.HIGH, Path.of("."), emptyList(), () -> false),
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
                            Path.of("."),
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
                    "Always mention build files.",
                    ProviderAttachmentTestSupport.authority()
            );

            subject.executeTurn(
                    new AgentRunRequest(List.of(Message.user("ping")), ReasoningLevel.OFF, Path.of("."), emptyList(), () -> false),
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
                    "token",
                    ProviderAttachmentTestSupport.authority()
            );

            List<String> errors = new ArrayList<>();
            AgentTurnResult turnResult = subject.executeTurn(
                    new AgentRunRequest(List.of(Message.user("ping")), ReasoningLevel.OFF, Path.of("."), emptyList(), () -> false),
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
