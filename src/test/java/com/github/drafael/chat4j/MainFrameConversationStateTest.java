package com.github.drafael.chat4j;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MainFrameConversationStateTest {

    @Test
    @DisplayName("Default state starts without current conversation and pending render mode")
    void defaults_whenConstructed_startWithEmptyConversationState() {
        var subject = new MainFrameConversationState();

        assertThat(subject.currentConversationId()).isNull();
        assertThat(subject.pendingUnsavedConversationRenderMode()).isNull();
    }

    @Test
    @DisplayName("Setters and clear helpers update tracked conversation state")
    void mutators_whenCalled_updateAndClearConversationState() {
        var subject = new MainFrameConversationState();
        var conversationId = UUID.randomUUID();

        subject.setCurrentConversationId(conversationId);
        subject.setPendingUnsavedConversationRenderMode(AssistantRenderMode.MARKDOWN);

        assertThat(subject.currentConversationId()).isEqualTo(conversationId);
        assertThat(subject.pendingUnsavedConversationRenderMode()).isEqualTo(AssistantRenderMode.MARKDOWN);

        subject.clearCurrentConversationId();
        subject.clearPendingUnsavedConversationRenderMode();

        assertThat(subject.currentConversationId()).isNull();
        assertThat(subject.pendingUnsavedConversationRenderMode()).isNull();
    }
}
