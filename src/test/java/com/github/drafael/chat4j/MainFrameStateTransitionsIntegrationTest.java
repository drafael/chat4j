package com.github.drafael.chat4j;

import com.github.drafael.chat4j.chat.NewChatCoordinator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MainFrameStateTransitionsIntegrationTest {

    @Test
    @DisplayName("New-chat transition clears conversation state without changing render mode")
    void newChat_whenStarted_clearsConversationStateOnly() {
        var conversationState = new MainFrameConversationState();
        UUID conversationId = UUID.randomUUID();
        conversationState.setCurrentConversationId(conversationId);
        var calls = new ArrayList<String>();

        new NewChatCoordinator().start(
                () -> calls.add("save"),
                () -> {
                    calls.add("clear-current");
                    conversationState.clearCurrentConversationId();
                },
                () -> calls.add("clear-active"),
                () -> calls.add("clear-selection"),
                () -> calls.add("clear-view"),
                () -> calls.add("reset-runtime"),
                () -> calls.add("focus")
        );

        assertThat(conversationState.currentConversationId()).isNull();
        assertThat(calls).containsExactly(
                "save",
                "clear-current",
                "clear-active",
                "clear-selection",
                "clear-view",
                "reset-runtime",
                "focus"
        );
    }
}
