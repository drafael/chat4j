package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;

import java.util.UUID;

public class AssistantRenderModeChangePlanner {

    public ChangePlan plan(UUID currentConversationId, AssistantRenderMode mode) {
        if (mode == null) {
            return ChangePlan.ignorePlan();
        }

        if (currentConversationId != null) {
            return ChangePlan.persistPlan(currentConversationId, mode);
        }

        return ChangePlan.pendingPlan(mode);
    }

    public record ChangePlan(
            boolean ignore,
            UUID conversationIdToPersist,
            AssistantRenderMode modeToPersist,
            AssistantRenderMode pendingUnsavedMode
    ) {

        private static ChangePlan ignorePlan() {
            return new ChangePlan(true, null, null, null);
        }

        private static ChangePlan persistPlan(UUID conversationIdToPersist, AssistantRenderMode modeToPersist) {
            return new ChangePlan(false, conversationIdToPersist, modeToPersist, null);
        }

        private static ChangePlan pendingPlan(AssistantRenderMode pendingUnsavedMode) {
            return new ChangePlan(false, null, null, pendingUnsavedMode);
        }
    }
}
