package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import lombok.NonNull;

import java.util.UUID;

public class AssistantRenderModeChangeCoordinator {

    private final AssistantRenderModeChangePlanner assistantRenderModeChangePlanner;
    private final ConversationModePersister conversationModePersister;

    public AssistantRenderModeChangeCoordinator(
            AssistantRenderModeChangePlanner assistantRenderModeChangePlanner,
            AssistantRenderModeSettingsCoordinator assistantRenderModeSettingsCoordinator
    ) {
        this(
                assistantRenderModeChangePlanner,
                assistantRenderModeSettingsCoordinator::persistConversationMode
        );
    }

    AssistantRenderModeChangeCoordinator(
            @NonNull AssistantRenderModeChangePlanner assistantRenderModeChangePlanner,
            @NonNull ConversationModePersister conversationModePersister
    ) {
        this.assistantRenderModeChangePlanner = assistantRenderModeChangePlanner;
        this.conversationModePersister = conversationModePersister;
    }

    public ApplyResult apply(
            UUID currentConversationId,
            AssistantRenderMode mode,
            AssistantRenderMode currentPendingUnsavedConversationRenderMode
    ) {
        AssistantRenderModeChangePlanner.ChangePlan changePlan =
                assistantRenderModeChangePlanner.plan(currentConversationId, mode);
        if (changePlan.ignore()) {
            return ApplyResult.ignoredResult(currentPendingUnsavedConversationRenderMode);
        }

        if (changePlan.conversationIdToPersist() != null) {
            conversationModePersister.persist(changePlan.conversationIdToPersist(), changePlan.modeToPersist());
            return ApplyResult.handledResult(currentPendingUnsavedConversationRenderMode);
        }

        return ApplyResult.handledResult(changePlan.pendingUnsavedMode());
    }

    public record ApplyResult(boolean handled, AssistantRenderMode pendingUnsavedConversationRenderMode) {

        static ApplyResult ignoredResult(AssistantRenderMode pendingUnsavedConversationRenderMode) {
            return new ApplyResult(false, pendingUnsavedConversationRenderMode);
        }

        static ApplyResult handledResult(AssistantRenderMode pendingUnsavedConversationRenderMode) {
            return new ApplyResult(true, pendingUnsavedConversationRenderMode);
        }
    }

    @FunctionalInterface
    interface ConversationModePersister {
        void persist(UUID conversationId, AssistantRenderMode mode);
    }
}
