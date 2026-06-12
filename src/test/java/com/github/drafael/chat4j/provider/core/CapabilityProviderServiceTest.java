package com.github.drafael.chat4j.provider.core;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityProviderServiceTest {

    @Test
    @DisplayName("Rendering hint is prepended to normal chat history")
    void withChat4jRenderingHint_whenHistoryHasNoSystemMessage_prependsRenderableFenceInstructions() {
        List<Message> hinted = CapabilityProviderService.withChat4jRenderingHint(List.of(Message.user("draw benzene")));

        assertThat(hinted).hasSize(2);
        assertThat(hinted.getFirst().role()).isEqualTo(Role.SYSTEM);
        assertThat(hinted.getFirst().content())
                .contains("`mermaid`")
                .contains("`smiles`")
                .contains("output only a valid `smiles` fenced block")
                .contains("Do not output chemical coordinate-file fences")
                .contains("Coordinate-file rendering requires exact user-supplied file content")
                .contains("Do not use Mermaid for chemistry")
                .doesNotContain("`mol`")
                .doesNotContain("`sdf`");
        assertThat(hinted.get(1).content()).isEqualTo("draw benzene");
    }

    @Test
    @DisplayName("Existing system messages are preserved without adding a duplicate rendering hint")
    void withChat4jRenderingHint_whenHistoryHasSystemMessage_preservesHistory() {
        List<Message> history = List.of(Message.system("Custom instruction"), Message.user("Hello"));

        List<Message> hinted = CapabilityProviderService.withChat4jRenderingHint(history);

        assertThat(hinted).isSameAs(history);
    }

    @Test
    @DisplayName("Normal streaming sends the rendering hint to the chat client")
    void streamCompletion_whenNormalChat_routesHintedHistoryToClient() {
        AtomicReference<List<Message>> observedHistory = new AtomicReference<>();
        var subject = new CapabilityProviderService(runtime(), recordingClient(observedHistory), runtime -> List.of());

        subject.streamCompletion(
                List.of(Message.user("show ethanol as MOL")),
                ReasoningLevel.OFF,
                ignored -> {},
                ignored -> {},
                () -> {},
                error -> {},
                () -> false
        );

        assertThat(observedHistory.get()).isNotNull();
        assertThat(observedHistory.get().getFirst().role()).isEqualTo(Role.SYSTEM);
        assertThat(observedHistory.get().getFirst().content())
                .contains("`smiles`")
                .doesNotContain("`mol`");
    }

    @Test
    @DisplayName("Web-search streaming also sends the rendering hint to the chat client")
    void streamCompletion_whenWebSearchChat_routesHintedHistoryToClient() {
        AtomicReference<List<Message>> observedHistory = new AtomicReference<>();
        var subject = new CapabilityProviderService(runtime(), recordingClient(observedHistory), runtime -> List.of());

        subject.streamCompletion(
                List.of(Message.user("show benzene SDF")),
                ReasoningLevel.OFF,
                WebSearchRequestOptions.disabled(),
                ignored -> {},
                ignored -> {},
                () -> {},
                error -> {},
                () -> false
        );

        assertThat(observedHistory.get()).isNotNull();
        assertThat(observedHistory.get().getFirst().role()).isEqualTo(Role.SYSTEM);
        assertThat(observedHistory.get().getFirst().content())
                .contains("`smiles`")
                .doesNotContain("`sdf`");
    }

    private static ProviderRuntime runtime() {
        return new ProviderRuntime(
                new ProviderDescriptor(
                        "Test",
                        AuthType.ENV_VAR,
                        "TEST_API_KEY",
                        null,
                        "https://example.invalid",
                        List.of(),
                        ProviderCapabilities.chatAndModels(),
                        value -> value
                ),
                "TEST_API_KEY",
                "https://example.invalid",
                "test-key",
                "test-model"
        );
    }

    private static ChatCompletionClient recordingClient(AtomicReference<List<Message>> observedHistory) {
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
                observedHistory.set(history);
            }

            @Override
            public void streamCompletion(
                    ProviderRuntime runtime,
                    List<Message> history,
                    ReasoningLevel reasoningLevel,
                    WebSearchRequestOptions webSearchOptions,
                    Consumer<String> onToken,
                    Consumer<String> onThinkingToken,
                    BooleanSupplier isCancelled,
                    Consumer<AutoCloseable> registerActiveStream,
                    Runnable clearActiveStream
            ) {
                observedHistory.set(history);
            }
        };
    }
}
