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

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MistralChatCompletionClientTest {

    @Test
    @DisplayName("Ordinary Mistral requests keep the Chat Completions delegate")
    void streamCompletion_whenSearchIsDisabled_usesOrdinaryDelegate() throws Exception {
        var ordinaryCalled = new AtomicBoolean();
        var nativeCalled = new AtomicBoolean();
        var subject = new MistralChatCompletionClient(client(ordinaryCalled), client(nativeCalled));

        stream(subject, runtime("mistral-small-latest", "https://api.mistral.ai/v1"), WebSearchRequestOptions.disabled());

        assertThat(ordinaryCalled).isTrue();
        assertThat(nativeCalled).isFalse();
    }

    @Test
    @DisplayName("Eligible search requests use only the Conversations delegate")
    void streamCompletion_whenSearchIsSupported_usesNativeDelegate() throws Exception {
        var ordinaryCalled = new AtomicBoolean();
        var nativeCalled = new AtomicBoolean();
        var subject = new MistralChatCompletionClient(client(ordinaryCalled), client(nativeCalled));

        stream(
                subject,
                runtime("mistral-small-latest", "https://api.mistral.ai"),
                new WebSearchRequestOptions(true)
        );

        assertThat(ordinaryCalled).isFalse();
        assertThat(nativeCalled).isTrue();
    }

    @Test
    @DisplayName("Unsupported search fails before either transport starts")
    void streamCompletion_whenSearchIsUnsupported_failsClosed() {
        var ordinaryCalled = new AtomicBoolean();
        var nativeCalled = new AtomicBoolean();
        var subject = new MistralChatCompletionClient(client(ordinaryCalled), client(nativeCalled));

        assertThatThrownBy(() -> stream(
                subject,
                runtime("mistral-small-latest", "https://proxy.example/v1"),
                new WebSearchRequestOptions(true)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("official API endpoint");
        assertThatThrownBy(() -> stream(
                subject,
                runtime("codestral-latest", "https://api.mistral.ai/v1"),
                new WebSearchRequestOptions(true)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supported chat model");
        assertThat(ordinaryCalled).isFalse();
        assertThat(nativeCalled).isFalse();
    }

    private void stream(
            MistralChatCompletionClient subject,
            ProviderRuntime runtime,
            WebSearchRequestOptions options
    ) throws Exception {
        subject.streamCompletion(
                runtime,
                List.of(Message.user("latest news")),
                ReasoningLevel.OFF,
                options,
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
                "Mistral",
                AuthType.ENV_VAR,
                "MISTRAL_API_KEY",
                null,
                baseUrl,
                List.of(),
                ProviderCapabilities.chatAndModels(),
                value -> value
        );
        return new ProviderRuntime(descriptor, "MISTRAL_API_KEY", baseUrl, "test-key", model);
    }
}
