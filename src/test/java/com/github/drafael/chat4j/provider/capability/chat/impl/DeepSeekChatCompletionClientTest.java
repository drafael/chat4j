package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepSeekChatCompletionClientTest {

    @ParameterizedTest
    @ValueSource(strings = {"deepseek-flash", "deepseek-flash[1m]", "deepseek-v4-pro"})
    @DisplayName("Ordinary DeepSeek requests keep the OpenAI-compatible delegate")
    void streamCompletion_whenSearchIsDisabled_usesOrdinaryDelegate(String modelId) throws Exception {
        var ordinaryCalled = new AtomicBoolean();
        var nativeCalled = new AtomicBoolean();
        var subject = new DeepSeekChatCompletionClient(client(ordinaryCalled), client(nativeCalled));

        subject.streamCompletion(
                runtime(modelId, "https://api.deepseek.com"),
                List.of(Message.user("hello")),
                ReasoningLevel.OFF,
                WebSearchRequestOptions.disabled(),
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

        assertThat(ordinaryCalled).isTrue();
        assertThat(nativeCalled).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek-flash", "deepseek-flash[1m]", "deepseek-v4-flash", "deepseek-v4-pro", "deepseek-v4-pro[1m]"})
    @DisplayName("Supported native search requests use only the Anthropic-compatible delegate")
    void streamCompletion_whenNativeSearchIsSupported_usesNativeDelegate(String modelId) throws Exception {
        var ordinaryCalled = new AtomicBoolean();
        var nativeCalled = new AtomicBoolean();
        var subject = new DeepSeekChatCompletionClient(client(ordinaryCalled), client(nativeCalled));

        subject.streamCompletion(
                runtime(modelId, "https://api.deepseek.com/v1"),
                List.of(Message.user("latest news")),
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

        assertThat(ordinaryCalled).isFalse();
        assertThat(nativeCalled).isTrue();
    }

    @Test
    @DisplayName("Unsupported native search fails before either transport starts")
    void streamCompletion_whenNativeSearchIsUnsupported_failsClosed() {
        var ordinaryCalled = new AtomicBoolean();
        var nativeCalled = new AtomicBoolean();
        var subject = new DeepSeekChatCompletionClient(client(ordinaryCalled), client(nativeCalled));

        assertThatThrownBy(() -> subject.streamCompletion(
                runtime("deepseek-chat", "https://api.deepseek.com"),
                List.of(Message.user("latest news")),
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
                .hasMessageContaining("supported model");
        assertThat(ordinaryCalled).isFalse();
        assertThat(nativeCalled).isFalse();
    }

    private ChatCompletionClient client(AtomicBoolean called) {
        return new ChatCompletionClient() {
            @Override
            public void streamCompletion(
                    ProviderRuntime runtime,
                    List<Message> history,
                    ReasoningLevel reasoningLevel,
                    Consumer<String> onToken,
                    Consumer<String> onThinkingToken,
                    BooleanSupplier isCancelled,
                    Consumer<AutoCloseable> registerActiveStream,
                    Runnable clearActiveStream
            ) {
                called.set(true);
            }
        };
    }

    private ProviderRuntime runtime(String model, String baseUrl) {
        var descriptor = new ProviderDescriptor(
                "DeepSeek",
                AuthType.ENV_VAR,
                "DEEPSEEK_API_KEY",
                null,
                baseUrl,
                List.of(),
                ProviderCapabilities.chatAndModels(),
                value -> value
        );
        return new ProviderRuntime(descriptor, "DEEPSEEK_API_KEY", baseUrl, "test-key", model);
    }
}
