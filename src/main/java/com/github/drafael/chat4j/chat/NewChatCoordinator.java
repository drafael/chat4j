package com.github.drafael.chat4j.chat;

import lombok.NonNull;

public class NewChatCoordinator {

    public void start(
            @NonNull Runnable saveCurrentConversation,
            @NonNull Runnable clearCurrentConversationId,
            @NonNull Runnable clearActiveConversationId,
            @NonNull Runnable clearSidebarSelection,
            @NonNull Runnable clearChatView,
            @NonNull Runnable resetAgentModeState,
            @NonNull Runnable requestInputFocus
    ) {

        saveCurrentConversation.run();
        clearCurrentConversationId.run();
        clearActiveConversationId.run();
        clearSidebarSelection.run();
        clearChatView.run();
        resetAgentModeState.run();
        requestInputFocus.run();
    }
}
