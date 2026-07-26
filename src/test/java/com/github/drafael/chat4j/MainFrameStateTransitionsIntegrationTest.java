package com.github.drafael.chat4j;

import com.github.drafael.chat4j.chat.NewChatCoordinator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MainFrameStateTransitionsIntegrationTest {

    @Test
    @DisplayName("A pending conversation load blocks another Sidebar conversation action")
    void conversationChangePending_whenLoadIsPending_returnsTrue() {
        assertThat(MainFrame.conversationChangePending(false, UUID.randomUUID(), false)).isTrue();
        assertThat(MainFrame.conversationChangePending(false, null, false)).isFalse();
    }

    @Test
    @DisplayName("A pending conversation operation blocks actions only for its targeted conversations")
    void conversationActionPending_whenTargetIsInPendingSet_returnsTrue() {
        UUID firstConversationId = UUID.randomUUID();
        UUID secondConversationId = UUID.randomUUID();
        Set<UUID> pendingConversationIds = Set.of(firstConversationId, secondConversationId);

        assertThat(MainFrame.conversationActionPending(pendingConversationIds, firstConversationId)).isTrue();
        assertThat(MainFrame.conversationActionPending(pendingConversationIds, secondConversationId)).isTrue();
        assertThat(MainFrame.conversationActionPending(pendingConversationIds, UUID.randomUUID())).isFalse();
        assertThat(MainFrame.conversationActionPending(pendingConversationIds, null)).isFalse();
    }

    @Test
    @DisplayName("New-chat transition clears conversation state without changing render mode")
    void newChat_whenStarted_clearsConversationStateOnly() {
        var conversationState = new MainFrameConversationState();
        UUID conversationId = UUID.randomUUID();
        conversationState.setCurrentConversationId(conversationId);
        var calls = new ArrayList<String>();

        new NewChatCoordinator().start(
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
                "clear-current",
                "clear-active",
                "clear-selection",
                "clear-view",
                "reset-runtime",
                "focus"
        );
    }
}
