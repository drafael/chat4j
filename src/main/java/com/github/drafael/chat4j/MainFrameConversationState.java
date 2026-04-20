package com.github.drafael.chat4j;

import com.github.drafael.chat4j.chat.AssistantRenderMode;

import java.util.UUID;

public class MainFrameConversationState {

    private UUID currentConversationId;
    private AssistantRenderMode pendingUnsavedConversationRenderMode;

    public UUID currentConversationId() {
        return currentConversationId;
    }

    public AssistantRenderMode pendingUnsavedConversationRenderMode() {
        return pendingUnsavedConversationRenderMode;
    }

    public void setCurrentConversationId(UUID currentConversationId) {
        this.currentConversationId = currentConversationId;
    }

    public void setPendingUnsavedConversationRenderMode(AssistantRenderMode pendingUnsavedConversationRenderMode) {
        this.pendingUnsavedConversationRenderMode = pendingUnsavedConversationRenderMode;
    }

    public void clearCurrentConversationId() {
        currentConversationId = null;
    }

    public void clearPendingUnsavedConversationRenderMode() {
        pendingUnsavedConversationRenderMode = null;
    }
}
