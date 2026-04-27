package com.github.drafael.chat4j.chat;

import org.apache.commons.lang3.Validate;

public class NewChatCoordinator {

    public void start(
            Runnable saveCurrentConversation,
            Runnable clearCurrentConversationId,
            Runnable clearPendingUnsavedConversationRenderMode,
            Runnable clearActiveConversationId,
            Runnable clearSidebarSelection,
            Runnable clearChatView,
            Runnable resetAgentModeState,
            AssistantRenderMode defaultAssistantRenderMode,
            RenderModeApplier renderModeApplier,
            Runnable requestInputFocus
    ) {
        Validate.notNull(saveCurrentConversation, "saveCurrentConversation must not be null");
        Validate.notNull(clearCurrentConversationId, "clearCurrentConversationId must not be null");
        Validate.notNull(
                clearPendingUnsavedConversationRenderMode,
                "clearPendingUnsavedConversationRenderMode must not be null"
        );
        Validate.notNull(clearActiveConversationId, "clearActiveConversationId must not be null");
        Validate.notNull(clearSidebarSelection, "clearSidebarSelection must not be null");
        Validate.notNull(clearChatView, "clearChatView must not be null");
        Validate.notNull(resetAgentModeState, "resetAgentModeState must not be null");
        Validate.notNull(defaultAssistantRenderMode, "defaultAssistantRenderMode must not be null");
        Validate.notNull(renderModeApplier, "renderModeApplier must not be null");
        Validate.notNull(requestInputFocus, "requestInputFocus must not be null");

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
