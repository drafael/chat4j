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

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                        "content": "I will read the file.",
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

            List<String> tokens = new ArrayList<>();
            AgentTurnResult firstTurn = subject.executeTurn(
                    new AgentRunRequest(List.of(Message.user("read note")), ReasoningLevel.OFF, Path.of("."), emptyList(), () -> false),
                    new AgentRunCallbacks(tokens::add, thinking -> {
                    }, () -> {
                    }, error -> {
                    })
            );

            assertThat(firstTurn.completed()).isFalse();
            assertThat(firstTurn.toolInvocations()).hasSize(1);
            assertThat(firstTurn.toolInvocations().getFirst().name()).isEqualTo("read");
            assertThat(tokens).isEmpty();

            AgentTurnResult secondTurn = subject.executeTurn(
                    new AgentRunRequest(
                            List.of(Message.user("read note")),
                            ReasoningLevel.OFF,
                            Path.of("."),
                            List.of(new ToolInvocationResult("call_1", "read", true, "note content", "")),
                            () -> false
                    ),
                    new AgentRunCallbacks(tokens::add, thinking -> {
                    }, () -> {
                    }, error -> {
                    })
            );

            assertThat(secondTurn.completed()).isTrue();
            assertThat(secondTurn.toolInvocations()).isEmpty();
            assertThat(tokens).containsExactly("done");
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

    @Test
    @DisplayName("Reasoning callbacks are suppressed when Agent Mode reasoning is off")
    void executeTurn_whenReasoningIsOff_suppressesThinkingCallback() throws Exception {
        List<String> requestBodies = new ArrayList<>();
        HttpServer server = createChatCompletionsServer(
                List.of("{\"choices\":[{\"message\":{\"content\":\"done\",\"reasoning\":\"hidden\"}}]}"),
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
            List<String> thinking = new ArrayList<>();

            AgentTurnResult result = subject.executeTurn(
                    new AgentRunRequest(
                            List.of(Message.user("question")),
                            ReasoningLevel.OFF,
                            Path.of("."),
                            emptyList(),
                            () -> false
                    ),
                    new AgentRunCallbacks(token -> {
                    }, thinking::add, () -> {
                    }, error -> {
                    })
            );

            assertThat(result.completed()).isTrue();
            assertThat(thinking).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Reasoning callbacks precede final answer callbacks")
    void executeTurn_whenResponseContainsReasoning_emitsThinkingBeforeAnswer() throws Exception {
        List<String> requestBodies = new ArrayList<>();
        HttpServer server = createChatCompletionsServer(
                List.of("{\"choices\":[{\"message\":{\"content\":\"done\",\"reasoning\":\"analysis\"}}]}"),
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
            List<String> callbacks = new ArrayList<>();

            AgentTurnResult result = subject.executeTurn(
                    new AgentRunRequest(
                            List.of(Message.user("question")),
                            ReasoningLevel.HIGH,
                            Path.of("."),
                            emptyList(),
                            () -> false
                    ),
                    new AgentRunCallbacks(
                            token -> callbacks.add("answer:%s".formatted(token)),
                            thinking -> callbacks.add("thinking:%s".formatted(thinking)),
                            () -> {
                            },
                            error -> {
                            }
                    )
            );

            assertThat(result.completed()).isTrue();
            assertThat(callbacks).containsExactly("thinking:analysis", "answer:done");
            assertThat(requestBodies).hasSize(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Hosted Together Agent payloads use model-specific reasoning and GLM preserved thinking")
    void applyTogetherReasoning_whenHostedModelVaries_usesCentralPolicy() throws Exception {
        var glm = new OpenAiToolAgentAdapter(
                "Together",
                "zai-org/GLM-5.2",
                "https://api.together.ai/v1",
                "key",
                ProviderAttachmentTestSupport.authority()
        );
        Map<String, Object> enabled = applyTogetherReasoning(glm, ReasoningLevel.EXTRA_HIGH);
        Map<String, Object> disabled = applyTogetherReasoning(glm, ReasoningLevel.OFF);
        var custom = new OpenAiToolAgentAdapter(
                "Together",
                "zai-org/GLM-5.2",
                "https://proxy.example/v1",
                "key",
                ProviderAttachmentTestSupport.authority()
        );

        assertThat(enabled)
                .containsEntry("reasoning_effort", "max")
                .containsEntry("chat_template_kwargs", Map.of("clear_thinking", false));
        assertThat(disabled).containsEntry("reasoning", Map.of("enabled", false));
        assertThat(disabled).doesNotContainKeys("reasoning_effort", "chat_template_kwargs");
        assertThat(applyTogetherReasoning(custom, ReasoningLevel.EXTRA_HIGH)).isEmpty();
    }

    @Test
    @DisplayName("Hosted Together validates and preserves assistant content, tool calls, and exact DeepSeek reasoning field")
    @SuppressWarnings("unchecked")
    void validateResponse_whenHostedDeepSeekReturnsToolCalls_preservesCoherentContinuation() throws Exception {
        var subject = new OpenAiToolAgentAdapter(
                "Together",
                "deepseek-ai/DeepSeek-V4-Pro",
                "https://api.together.ai/v1",
                "key",
                ProviderAttachmentTestSupport.authority()
        );
        JsonNode response = JSON.readTree("""
                {
                  "choices": [{
                    "finish_reason": "tool_calls",
                    "message": {
                      "role": "assistant",
                      "content": "I will inspect both files.",
                      "reasoning_content": "exact trace",
                      "tool_calls": [
                        {"id":"call_1","type":"function","provider_extension":{"trace":7},"function":{"name":"read","arguments":"{\\"path\\":\\"a\\"}"}},
                        {"id":"call_2","type":"function","function":{"name":"read","arguments":"{\\"path\\":\\"b\\"}"}}
                      ]
                    }
                  }]
                }
                """);

        Object validated = invokeValidateResponse(subject, response, ReasoningLevel.HIGH);
        Object continuation = invokeAccessor(validated, "continuation");
        Field pending = OpenAiToolAgentAdapter.class.getDeclaredField("pendingAssistantContinuation");
        pending.setAccessible(true);
        pending.set(subject, continuation);
        Method prepare = OpenAiToolAgentAdapter.class.getDeclaredMethod("prepareToolExchange", List.class);
        prepare.setAccessible(true);
        Object exchange = prepare.invoke(subject, List.of(
                new ToolInvocationResult("call_1", "read", true, "a", ""),
                new ToolInvocationResult("call_2", "read", true, "b", "")
        ));
        Method commit = OpenAiToolAgentAdapter.class.getDeclaredMethod("commitToolExchange", exchange.getClass());
        commit.setAccessible(true);
        commit.invoke(subject, exchange);
        Field exchanges = OpenAiToolAgentAdapter.class.getDeclaredField("toolExchangeMessages");
        exchanges.setAccessible(true);
        List<Map<String, Object>> messages = (List<Map<String, Object>>) exchanges.get(subject);

        assertThat((List<?>) invokeAccessor(validated, "toolInvocations")).hasSize(2);
        assertThat(messages.getFirst())
                .containsEntry("content", "I will inspect both files.")
                .containsEntry("reasoning_content", "exact trace");
        List<Map<String, Object>> preservedCalls = (List<Map<String, Object>>) messages.getFirst().get("tool_calls");
        assertThat(preservedCalls).hasSize(2);
        assertThat(preservedCalls.getFirst()).containsEntry(
                "provider_extension",
                Map.of("trace", 7)
        );
        assertThat(messages.subList(1, 3))
                .extracting(message -> message.get("tool_call_id"))
                .containsExactly("call_1", "call_2");
    }

    @Test
    @DisplayName("Hosted DeepSeek replays canonical reasoning under the exact returned field name")
    void validateResponse_whenHostedDeepSeekReturnsCanonicalReasoning_replaysCanonicalField() throws Exception {
        var subject = new OpenAiToolAgentAdapter(
                "Together",
                "deepseek-ai/DeepSeek-V4-Pro",
                "https://api.together.ai/v1",
                "key",
                ProviderAttachmentTestSupport.authority()
        );
        JsonNode response = JSON.readTree("""
                {"choices":[{"message":{"role":"assistant","content":null,"reasoning":"canonical trace","tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"read","arguments":"{}"}}
                ]}}]}
                """);

        Object continuation = invokeAccessor(
                invokeValidateResponse(subject, response, ReasoningLevel.HIGH),
                "continuation"
        );
        Object reasoning = invokeAccessor(continuation, "reasoning");

        assertThat(invokeAccessor(reasoning, "fieldName")).isEqualTo("reasoning");
        assertThat(invokeAccessor(reasoning, "value")).isEqualTo("canonical trace");
    }

    @Test
    @DisplayName("GLM replays canonical reasoning only when enabled and other Together models do not inherit continuation")
    void validateResponse_whenTogetherContinuationPolicyVaries_keepsOnlyDocumentedReasoning() throws Exception {
        JsonNode response = JSON.readTree("""
                {"choices":[{"message":{"role":"assistant","content":null,"reasoning":"trace","tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"read","arguments":"{}"}}
                ]}}]}
                """);
        var glm = new OpenAiToolAgentAdapter(
                "Together",
                "zai-org/GLM-5.2",
                "https://api.together.ai/v1",
                "key",
                ProviderAttachmentTestSupport.authority()
        );
        var minimax = new OpenAiToolAgentAdapter(
                "Together",
                "MiniMaxAI/MiniMax-M3",
                "https://api.together.ai/v1",
                "key",
                ProviderAttachmentTestSupport.authority()
        );

        Object glmEnabled = invokeAccessor(
                invokeValidateResponse(glm, response, ReasoningLevel.HIGH),
                "continuation"
        );
        Object glmDisabled = invokeAccessor(
                invokeValidateResponse(glm, response, ReasoningLevel.OFF),
                "continuation"
        );
        Object otherTogether = invokeAccessor(
                invokeValidateResponse(minimax, response, ReasoningLevel.HIGH),
                "continuation"
        );

        Object glmReasoning = invokeAccessor(glmEnabled, "reasoning");
        assertThat(invokeAccessor(glmReasoning, "fieldName")).isEqualTo("reasoning");
        assertThat(invokeAccessor(glmReasoning, "value")).isEqualTo("trace");
        assertThat(invokeAccessor(glmDisabled, "reasoning")).isNull();
        assertThat(invokeAccessor(otherTogether, "reasoning")).isNull();
    }

    @Test
    @DisplayName("Hosted Together rejects malformed response and finish-reason shapes before callbacks")
    void validateResponse_whenHostedTogetherShapeIsInvalid_rejectsWholeResponse() throws Exception {
        var subject = new OpenAiToolAgentAdapter(
                "Together",
                "Qwen/Qwen3.5-9B",
                "https://api.together.ai/v1",
                "key",
                ProviderAttachmentTestSupport.authority()
        );
        List<String> invalidResponses = List.of(
                "{}",
                "{\"choices\":[]}",
                "{\"choices\":[{\"message\":null}]}",
                "{\"choices\":[{\"message\":{\"role\":\"user\",\"content\":\"x\"}}]}",
                "{\"choices\":[{\"message\":{\"role\":\"assistant\"}}]}",
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":[],\"tool_calls\":[]}}]}",
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"role\":\"assistant\",\"content\":\"partial\"}}]}",
                "{\"choices\":[{\"finish_reason\":null,\"message\":{\"role\":\"assistant\",\"content\":\"done\"}}]}",
                "{\"choices\":[{\"finish_reason\":{},\"message\":{\"role\":\"assistant\",\"content\":\"done\"}}]}",
                "{\"choices\":[{\"finish_reason\":[],\"message\":{\"role\":\"assistant\",\"content\":\"done\"}}]}",
                "{\"choices\":[{\"finish_reason\":7,\"message\":{\"role\":\"assistant\",\"content\":\"done\"}}]}",
                "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":null}}]}",
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":{}}}]}",
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"x\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":{}}}]}}]}",
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"x\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}},{\"id\":\"x\",\"type\":\"function\",\"function\":{\"name\":\"ls\",\"arguments\":\"{}\"}}]}}]}"
        );

        assertThat(invalidResponses).allSatisfy(json -> assertThatThrownBy(
                () -> invokeValidateResponse(subject, JSON.readTree(json), ReasoningLevel.HIGH)
        ).isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid response"));
    }

    @Test
    @DisplayName("Missing and future hosted Together finish reasons remain compatible with valid bodies")
    void validateResponse_whenHostedFinishReasonIsMissingOrFuture_acceptsValidBody() throws Exception {
        var subject = new OpenAiToolAgentAdapter(
                "Together",
                "Qwen/Qwen3.5-9B",
                "https://api.together.ai/v1",
                "key",
                ProviderAttachmentTestSupport.authority()
        );

        assertThat(invokeValidateResponse(
                subject,
                JSON.readTree("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"done\"}}]}"),
                ReasoningLevel.OFF
        )).isNotNull();
        assertThat(invokeValidateResponse(
                subject,
                JSON.readTree("{\"choices\":[{\"finish_reason\":\"future_alias\",\"message\":{\"role\":\"assistant\",\"content\":\"done\"}}]}"),
                ReasoningLevel.OFF
        )).isNotNull();
    }

    @Test
    @DisplayName("A malformed continuation response leaves the exact pending tool exchange retryable")
    void executeTurn_whenContinuationResponseIsMalformed_preservesPendingExchangeForRetry() throws Exception {
        List<String> requestBodies = new ArrayList<>();
        HttpServer server = createChatCompletionsServer(
                List.of(
                        """
                                {"choices":[{"message":{"content":null,"tool_calls":[
                                  {"id":"call_1","type":"function","provider_extension":{"trace":7},"function":{"name":"read","arguments":""}}
                                ]}}]}
                                """,
                        "{}",
                        "{\"choices\":[{\"message\":{\"content\":\"done\"}}]}"
                ),
                requestBodies,
                List.of(200, 200, 200)
        );
        try {
            var subject = new OpenAiToolAgentAdapter(
                    "OpenAI",
                    "gpt-5",
                    "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort()),
                    "key",
                    ProviderAttachmentTestSupport.authority()
            );
            List<String> tokens = new ArrayList<>();
            List<Exception> errors = new ArrayList<>();
            AgentRunCallbacks callbacks = new AgentRunCallbacks(tokens::add, thinking -> {
            }, () -> {
            }, errors::add);
            AgentRunRequest initialRequest = new AgentRunRequest(
                    List.of(Message.user("question")),
                    ReasoningLevel.OFF,
                    Path.of("."),
                    emptyList(),
                    () -> false
            );

            AgentTurnResult initial = subject.executeTurn(initialRequest, callbacks);
            assertThat(initial.toolInvocations()).singleElement().satisfies(invocation -> assertThat(invocation.argumentsJson()).isEmpty());
            List<ToolInvocationResult> results = List.of(
                    new ToolInvocationResult("call_1", "read", true, "result", "")
            );
            AgentTurnResult failed = subject.executeTurn(initialRequest.withToolResults(results), callbacks);

            assertThat(failed.completed()).isFalse();
            assertThat(errors).singleElement().satisfies(error -> assertThat(error).hasMessageContaining("invalid response"));
            assertThat(tokens).isEmpty();
            errors.clear();

            AgentTurnResult retried = subject.executeTurn(initialRequest.withToolResults(results), callbacks);

            assertThat(retried.completed()).isTrue();
            assertThat(tokens).containsExactly("done");
            assertThat(errors).isEmpty();
            JsonNode failedRequest = JSON.readTree(requestBodies.get(1));
            JsonNode retryRequest = JSON.readTree(requestBodies.get(2));
            JsonNode failedAssistant = failedRequest.path("messages").path(2);
            JsonNode retryAssistant = retryRequest.path("messages").path(2);
            assertThat(failedAssistant).isEqualTo(retryAssistant);
            assertThat(failedAssistant.path("tool_calls").path(0).path("provider_extension").path("trace").asInt())
                    .isEqualTo(7);
            assertThat(failedAssistant.path("tool_calls").path(0).path("function").path("arguments").asText())
                    .isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("A callback failure leaves the pending tool exchange retryable")
    void executeTurn_whenContinuationCallbackFails_preservesPendingExchangeForRetry() throws Exception {
        assertPendingExchangeRemainsRetryableAfterCallback((attempt, cancelled) -> {
            if (attempt.incrementAndGet() == 1) {
                throw new IllegalStateException("callback failed");
            }
        });
    }

    @Test
    @DisplayName("Cancellation during a continuation callback leaves the pending tool exchange retryable")
    void executeTurn_whenContinuationCallbackCancels_preservesPendingExchangeForRetry() throws Exception {
        assertPendingExchangeRemainsRetryableAfterCallback((attempt, cancelled) -> {
            if (attempt.incrementAndGet() == 1) {
                cancelled.set(true);
            }
        });
    }

    private void assertPendingExchangeRemainsRetryableAfterCallback(
            java.util.function.BiConsumer<AtomicInteger, AtomicBoolean> firstCallback
    ) throws Exception {
        List<String> requestBodies = new ArrayList<>();
        HttpServer server = createChatCompletionsServer(
                List.of(
                        """
                                {"choices":[{"message":{"content":null,"tool_calls":[
                                  {"id":"call_1","type":"function","function":{"name":"read","arguments":"{}"}}
                                ]}}]}
                                """,
                        "{\"choices\":[{\"message\":{\"content\":\"first delivery\"}}]}",
                        "{\"choices\":[{\"message\":{\"content\":\"retried delivery\"}}]}"
                ),
                requestBodies,
                List.of(200, 200, 200)
        );
        try {
            var subject = new OpenAiToolAgentAdapter(
                    "OpenAI",
                    "gpt-5",
                    "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort()),
                    "key",
                    ProviderAttachmentTestSupport.authority()
            );
            var callbackAttempts = new AtomicInteger();
            var cancelled = new AtomicBoolean();
            List<String> tokens = new ArrayList<>();
            List<Exception> errors = new ArrayList<>();
            AgentRunRequest initialRequest = new AgentRunRequest(
                    List.of(Message.user("question")),
                    ReasoningLevel.OFF,
                    Path.of("."),
                    emptyList(),
                    cancelled::get
            );
            AgentTurnResult initial = subject.executeTurn(
                    initialRequest,
                    new AgentRunCallbacks(token -> { }, thinking -> { }, () -> { }, errors::add)
            );
            List<ToolInvocationResult> results = List.of(
                    new ToolInvocationResult("call_1", "read", true, "result", "")
            );
            AgentRunCallbacks firstCallbacks = new AgentRunCallbacks(token -> {
                tokens.add(token);
                firstCallback.accept(callbackAttempts, cancelled);
            }, thinking -> { }, () -> { }, errors::add);

            AgentTurnResult failed = subject.executeTurn(initialRequest.withToolResults(results), firstCallbacks);
            cancelled.set(false);
            AgentTurnResult retried = subject.executeTurn(
                    initialRequest.withToolResults(results),
                    new AgentRunCallbacks(tokens::add, thinking -> { }, () -> { }, errors::add)
            );

            assertThat(initial.toolInvocations()).hasSize(1);
            assertThat(failed.completed()).isFalse();
            assertThat(retried.completed()).isTrue();
            assertThat(tokens).containsExactly("first delivery", "retried delivery");
            JsonNode failedAssistant = JSON.readTree(requestBodies.get(1)).path("messages").path(2);
            JsonNode retryAssistant = JSON.readTree(requestBodies.get(2)).path("messages").path(2);
            assertThat(retryAssistant).isEqualTo(failedAssistant);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Agent HTTP errors redact structured fields and custom Together keeps generic status semantics")
    void executeTurn_whenCustomTogetherReturns403_redactsAndMapsGenericAuthentication() throws Exception {
        String key = "secret-key";
        String response = """
                {"error":{"type":"permission_secret-key","code":"secret-key","message":"context length secret-key"}}
                """;
        List<String> requestBodies = new ArrayList<>();
        HttpServer server = createChatCompletionsServer(List.of(response), requestBodies, List.of(403));
        try {
            var subject = new OpenAiToolAgentAdapter(
                    "Together",
                    "Qwen/Qwen3.5-9B",
                    "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort()),
                    key,
                    ProviderAttachmentTestSupport.authority()
            );
            List<Exception> errors = new ArrayList<>();

            subject.executeTurn(
                    new AgentRunRequest(List.of(Message.user("x")), ReasoningLevel.OFF, Path.of("."), emptyList(), () -> false),
                    new AgentRunCallbacks(token -> {
                    }, thinking -> {
                    }, () -> {
                    }, errors::add)
            );

            assertThat(errors).singleElement().satisfies(error -> assertThat(error)
                    .isInstanceOf(com.github.drafael.chat4j.provider.core.error.AuthenticationException.class)
                    .hasMessageNotContaining(key)
                    .hasMessageContaining("[REDACTED]"));
        } finally {
            server.stop(0);
        }
    }

    private Map<String, Object> applyTogetherReasoning(
            OpenAiToolAgentAdapter subject,
            ReasoningLevel reasoningLevel
    ) throws Exception {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        Method method = OpenAiToolAgentAdapter.class.getDeclaredMethod(
                "applyTogetherReasoning",
                Map.class,
                ReasoningLevel.class
        );
        method.setAccessible(true);
        method.invoke(subject, payload, reasoningLevel);
        return payload;
    }

    private Object invokeValidateResponse(
            OpenAiToolAgentAdapter subject,
            JsonNode response,
            ReasoningLevel reasoningLevel
    ) throws Exception {
        Method method = OpenAiToolAgentAdapter.class.getDeclaredMethod(
                "validateResponse",
                Map.class,
                ReasoningLevel.class
        );
        method.setAccessible(true);
        try {
            return method.invoke(subject, JSON.convertValue(response, Map.class), reasoningLevel);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private Object invokeAccessor(Object record, String accessor) throws Exception {
        Method method = record.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return method.invoke(record);
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
