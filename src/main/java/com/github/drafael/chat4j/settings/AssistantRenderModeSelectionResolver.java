package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import lombok.NonNull;

import java.util.UUID;

public class AssistantRenderModeSelectionResolver {

    public AssistantRenderMode resolve(
            UUID currentConversationId,
            AssistantRenderMode conversationMode,
            AssistantRenderMode pendingUnsavedConversationMode,
            @NonNull AssistantRenderMode defaultMode
    ) {

        if (currentConversationId != null) {
            return conversationMode != null ? conversationMode : defaultMode;
        }

        return pendingUnsavedConversationMode != null ? pendingUnsavedConversationMode : defaultMode;
    }
}
