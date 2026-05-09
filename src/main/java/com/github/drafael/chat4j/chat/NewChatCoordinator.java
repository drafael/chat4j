package com.github.drafael.chat4j.chat;

import lombok.NonNull;

public class NewChatCoordinator {

    public void start(
            @NonNull Runnable saveCurrentConversation,
            @NonNull Runnable clearCurrentConversationId,
            @NonNull Runnable clearPendingUnsavedConversationRenderMode,
            @NonNull Runnable clearActiveConversationId,
            @NonNull Runnable clearSidebarSelection,
            @NonNull Runnable clearChatView,
            @NonNull Runnable resetAgentModeState,
            @NonNull AssistantRenderMode defaultAssistantRenderMode,
            @NonNull RenderModeApplier renderModeApplier,
            @NonNull Runnable requestInputFocus
    ) {

        saveCurrentConversation.run();
        clearCurrentConversationId.run();
        clearPendingUnsavedConversationRenderMode.run();
        clearActiveConversationId.run();
        clearSidebarSelection.run();
        clearChatView.run();
        resetAgentModeState.run();
        renderModeApplier.apply(defaultAssistantRenderMode, true);
        requestInputFocus.run();
    }

    @FunctionalInterface
    public interface RenderModeApplier {
        void apply(AssistantRenderMode mode, boolean userInitiated);
    }
}
