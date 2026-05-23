package com.github.drafael.chat4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MainFrameConversationStateTest {

    @Test
    @DisplayName("Conversation state tracks current conversation id")
    void currentConversationId_whenSetAndCleared_updatesState() {
        var subject = new MainFrameConversationState();
        UUID conversationId = UUID.randomUUID();

        subject.setCurrentConversationId(conversationId);
        assertThat(subject.currentConversationId()).isEqualTo(conversationId);

        subject.clearCurrentConversationId();
        assertThat(subject.currentConversationId()).isNull();
    }
}
