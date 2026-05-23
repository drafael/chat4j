package com.github.drafael.chat4j;

import java.util.UUID;

public class MainFrameConversationState {

    private UUID currentConversationId;

    public UUID currentConversationId() {
        return currentConversationId;
    }

    public void setCurrentConversationId(UUID currentConversationId) {
        this.currentConversationId = currentConversationId;
    }

    public void clearCurrentConversationId() {
        currentConversationId = null;
    }
}
