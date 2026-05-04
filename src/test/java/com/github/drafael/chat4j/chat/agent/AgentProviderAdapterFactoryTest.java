package com.github.drafael.chat4j.chat.agent;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class AgentProviderAdapterFactoryTest {

    private final AgentProviderAdapterFactory subject = new AgentProviderAdapterFactory();

    @Test
    @DisplayName("Codex provider falls back to provider service adapter when OAuth bearer token is missing")
    void create_whenCodexApiKeyMissing_returnsProviderServiceAdapter() {
        AgentProviderAdapter adapter = subject.create(
                "OpenAI Codex",
                "gpt-5.5",
                "https://api.openai.com/v1",
                "",
                providerService(),
                ""
        );

        assertThat(adapter).isInstanceOf(ProviderServiceAgentAdapter.class);
    }

    @Test
    @DisplayName("Codex provider defaults to provider service adapter to match Codex CLI-first flow")
    void create_whenCodexApiKeyPresent_returnsProviderServiceAdapterByDefault() {
        AgentProviderAdapter adapter = subject.create(
                "OpenAI Codex",
                "gpt-5.5",
                "https://api.openai.com/v1",
                "token-123",
                providerService(),
                ""
        );

        assertThat(adapter).isInstanceOf(ProviderServiceAgentAdapter.class);
    }

    @Test
    @DisplayName("Google AI uses OpenAI-compatible fallback wrapper")
    void create_whenGoogleProvider_returnsOpenAiCompatibleFallbackAdapter() {
        AgentProviderAdapter adapter = subject.create(
                "Google AI",
                "gemini-3.1-pro-preview",
                "https://generativelanguage.googleapis.com/v1beta/openai",
                "token-123",
                providerService(),
                ""
        );

        assertThat(adapter).isInstanceOf(OpenAiCompatibleFallbackAgentAdapter.class);
    }

    @Test
    @DisplayName("Copilot uses OpenAI-compatible fallback wrapper")
    void create_whenCopilotProvider_returnsOpenAiCompatibleFallbackAdapter() {
        AgentProviderAdapter adapter = subject.create(
                "GitHub Copilot",
                "claude-sonnet-4.6",
                "https://api.githubcopilot.com/v1",
                "token-123",
                providerService(),
                ""
        );

        assertThat(adapter).isInstanceOf(OpenAiCompatibleFallbackAgentAdapter.class);
    }

    @Test
    @DisplayName("Copilot Claude models use Anthropic messages tool adapter")
    void create_whenCopilotClaudeModel_usesAnthropicMessagesToolAdapter() throws Exception {
        AtomicBoolean messagesEndpointCalled = new AtomicBoolean(false);
        AtomicBoolean fallbackInvoked = new AtomicBoolean(false);
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
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            messagesEndpointCalled.set(true);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            AgentProviderAdapter adapter = subject.create(
                    "GitHub Copilot",
                    "claude-haiku-4.5",
                    "http://127.0.0.1:%d".formatted(port),
                    "token-123",
                    providerService(() -> fallbackInvoked.set(true)),
                    ""
            );

            AgentTurnResult result = adapter.executeTurn(
                    new AgentRunRequest(List.of(Message.user("describe folder")), ReasoningLevel.OFF, Path.of("."), emptyList(), () -> false),
                    new AgentRunCallbacks(token -> {
                    }, thinking -> {
                    }, () -> {
                    }, error -> {
                    })
            );

            assertThat(adapter).isInstanceOf(OpenAiCompatibleFallbackAgentAdapter.class);
            assertThat(messagesEndpointCalled.get()).isTrue();
            assertThat(fallbackInvoked.get()).isFalse();
            assertThat(result.toolInvocations()).hasSize(1);
            assertThat(result.toolInvocations().getFirst().name()).isEqualTo("ls");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("LM Studio uses OpenAI-compatible fallback wrapper")
    void create_whenLmStudioProvider_returnsOpenAiCompatibleFallbackAdapter() {
        AgentProviderAdapter adapter = subject.create(
                "LM Studio",
                "gemma-4",
                "http://127.0.0.1:1234/v1",
                "",
                providerService(),
                ""
        );

        assertThat(adapter).isInstanceOf(OpenAiCompatibleFallbackAgentAdapter.class);
    }

    @Test
    @DisplayName("Ollama uses OpenAI-compatible fallback wrapper")
    void create_whenOllamaProvider_returnsOpenAiCompatibleFallbackAdapter() {
        AgentProviderAdapter adapter = subject.create(
                "Ollama",
                "gemma3",
                "http://127.0.0.1:11434/v1",
                "",
                providerService(),
                ""
        );

        assertThat(adapter).isInstanceOf(OpenAiCompatibleFallbackAgentAdapter.class);
    }

    @Test
    @DisplayName("Anthropic provider uses native Anthropic tool adapter")
    void create_whenAnthropicProvider_returnsAnthropicToolAdapter() {
        AgentProviderAdapter adapter = subject.create(
                "Anthropic",
                "claude-sonnet-4",
                "https://api.anthropic.com",
                "token-123",
                providerService(),
                ""
        );

        assertThat(adapter).isInstanceOf(AnthropicToolAgentAdapter.class);
    }

    private ProviderService providerService() {
        return providerService(() -> {
        });
    }

    private ProviderService providerService(Runnable onStreamCompletion) {
        return new ProviderService() {
            @Override
            public void streamCompletion(
                    List<Message> history,
                    ReasoningLevel reasoningLevel,
                    Consumer<String> onToken,
                    Consumer<String> onThinkingToken,
                    Runnable onComplete,
                    Consumer<Exception> onError,
                    BooleanSupplier isCancelled
            ) {
                onStreamCompletion.run();
                onComplete.run();
            }

            @Override
            public List<String> availableModels() {
                return List.of("test-model");
            }

            @Override
            public String name() {
                return "test";
            }

            @Override
            public String envVarName() {
                return "TEST_KEY";
            }
        };
    }
}
