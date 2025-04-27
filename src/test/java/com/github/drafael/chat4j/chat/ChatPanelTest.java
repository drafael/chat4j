package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.provider.api.ProviderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ChatPanelTest {

    private ChatPanel subject;

    @BeforeEach
    void setUp() {
        subject = new ChatPanel();
    }

    @Test
    @DisplayName("Selecting a non-seed model still creates a runtime provider for the selected provider")
    void setSelectedModel_whenModelIsNotPartOfSeedModels_createsProviderForSelectedProvider() throws Exception {
        subject.setSelectedModel("Ollama > llama3.2:latest");

        Object currentProvider = readCurrentProvider(subject);

        assertThat(subject.getSelectedModel()).isEqualTo("Ollama > llama3.2:latest");
        assertThat(currentProvider).isNotNull();
        assertThat(currentProvider).isInstanceOf(ProviderService.class);
    }

    @Test
    @DisplayName("Selecting an unavailable provider clears any previously selected runtime provider")
    void setSelectedModel_whenProviderIsUnavailable_clearsPreviousRuntimeProvider() throws Exception {
        subject.setSelectedModel("Ollama > llama3.2:latest");
        assertThat(readCurrentProvider(subject)).isNotNull();

        subject.setSelectedModel("UnavailableProvider > some-model");

        assertThat(readCurrentProvider(subject)).isNull();
    }

    @Test
    @DisplayName("Cancelling an active stream invalidates the session and clears streaming state")
    void cancelStreaming_whenStreamIsActive_invalidatesSessionAndClearsStreamingState() throws Exception {
        setField(subject, "streaming", true);
        setField(subject, "activeStreamSessionId", 42L);

        subject.cancelStreaming();

        assertThat((boolean) readField(subject, "streaming")).isFalse();
        assertThat((long) readField(subject, "activeStreamSessionId")).isEqualTo(-1L);
    }

    @Test
    @DisplayName("Auto-scroll setting can be enabled and disabled at runtime")
    void setAutoScrollEnabled_whenCalled_updatesAutoScrollBehaviorFlag() {
        subject.setAutoScrollEnabled(false);
        assertThat(subject.isAutoScrollEnabled()).isFalse();

        subject.setAutoScrollEnabled(true);
        assertThat(subject.isAutoScrollEnabled()).isTrue();
    }

    @Test
    @DisplayName("Assistant render mode switch updates state and emits change callback")
    void setAssistantRenderMode_whenChanged_updatesStateAndNotifiesListener() {
        var capturedMode = new AtomicReference<AssistantRenderMode>();
        subject.setOnAssistantRenderModeChanged(capturedMode::set);

        subject.setAssistantRenderMode(AssistantRenderMode.MARKDOWN, true);

        assertThat(subject.getAssistantRenderMode()).isEqualTo(AssistantRenderMode.MARKDOWN);
        assertThat(capturedMode.get()).isEqualTo(AssistantRenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Switching render mode rerenders loaded assistant bubbles")
    void setAssistantRenderMode_whenHistoryLoaded_updatesAssistantBubbleModes() throws Exception {
        subject.loadHistory(List.of(
                com.github.drafael.chat4j.provider.api.Message.user("hello"),
                com.github.drafael.chat4j.provider.api.Message.assistant("**bold**")
        ));

        subject.setAssistantRenderMode(AssistantRenderMode.MARKDOWN, true);

        @SuppressWarnings("unchecked")
        List<MessageBubble> assistantBubbles = (List<MessageBubble>) readField(subject, "assistantBubbles");
        assertThat(assistantBubbles).hasSize(1);
        assertThat(readBubbleRenderMode(assistantBubbles.getFirst())).isEqualTo(AssistantRenderMode.MARKDOWN);
    }

    private static Object readCurrentProvider(ChatPanel chatPanel) throws Exception {
        return readField(chatPanel, "currentProvider");
    }

    private static Object readField(ChatPanel chatPanel, String fieldName) throws Exception {
        Field field = ChatPanel.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(chatPanel);
    }

    private static void setField(ChatPanel chatPanel, String fieldName, Object value) throws Exception {
        Field field = ChatPanel.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(chatPanel, value);
    }

    private static AssistantRenderMode readBubbleRenderMode(MessageBubble bubble) throws Exception {
        Field field = MessageBubble.class.getDeclaredField("assistantRenderMode");
        field.setAccessible(true);
        return (AssistantRenderMode) field.get(bubble);
    }
}
