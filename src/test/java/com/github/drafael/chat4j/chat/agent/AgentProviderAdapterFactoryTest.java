package com.github.drafael.chat4j.chat.agent;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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
    @DisplayName("Codex provider uses fallback wrapper when OAuth bearer token is present")
    void create_whenCodexApiKeyPresent_returnsFallbackWrapperAdapter() {
        AgentProviderAdapter adapter = subject.create(
                "OpenAI Codex",
                "gpt-5.5",
                "https://api.openai.com/v1",
                "token-123",
                providerService(),
                ""
        );

        assertThat(adapter).isInstanceOf(CodexFallbackAgentAdapter.class);
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
