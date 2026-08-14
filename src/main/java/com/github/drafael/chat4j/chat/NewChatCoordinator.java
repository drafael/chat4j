package com.github.drafael.chat4j.chat;

import lombok.NonNull;

public class NewChatCoordinator {

    public void start(
            @NonNull Runnable clearCurrentConversationId,
            @NonNull Runnable clearActiveConversationId,
            @NonNull Runnable clearSidebarSelection,
            @NonNull Runnable clearChatView,
            @NonNull Runnable requestInputFocus
    ) {

        clearCurrentConversationId.run();
        clearActiveConversationId.run();
        clearSidebarSelection.run();
        clearChatView.run();
        requestInputFocus.run();
    }
}
