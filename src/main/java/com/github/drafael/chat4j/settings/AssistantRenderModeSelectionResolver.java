package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import org.apache.commons.lang3.Validate;

import java.util.UUID;

public class AssistantRenderModeSelectionResolver {

    public AssistantRenderMode resolve(
            UUID currentConversationId,
            AssistantRenderMode conversationMode,
            AssistantRenderMode pendingUnsavedConversationMode,
            AssistantRenderMode defaultMode
    ) {
        Validate.notNull(defaultMode, "defaultMode must not be null");

        if (currentConversationId != null) {
            return conversationMode != null ? conversationMode : defaultMode;
        }

        return pendingUnsavedConversationMode != null ? pendingUnsavedConversationMode : defaultMode;
    }
}
