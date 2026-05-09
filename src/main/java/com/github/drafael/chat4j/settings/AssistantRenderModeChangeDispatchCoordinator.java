package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import lombok.NonNull;

import java.util.UUID;
import java.util.function.Consumer;

public class AssistantRenderModeChangeDispatchCoordinator {

    private final ChangeApplyAction changeApplyAction;
    private final UiApplyAction uiApplyAction;

    public AssistantRenderModeChangeDispatchCoordinator(
            AssistantRenderModeChangeCoordinator assistantRenderModeChangeCoordinator,
            AssistantRenderModeChangeUiApplyCoordinator assistantRenderModeChangeUiApplyCoordinator
    ) {
        this(assistantRenderModeChangeCoordinator::apply, assistantRenderModeChangeUiApplyCoordinator::apply);
    }

    AssistantRenderModeChangeDispatchCoordinator(@NonNull ChangeApplyAction changeApplyAction, @NonNull UiApplyAction uiApplyAction) {
        this.changeApplyAction = changeApplyAction;
        this.uiApplyAction = uiApplyAction;
    }

    public boolean apply(
            UUID currentConversationId,
            @NonNull AssistantRenderMode mode,
            AssistantRenderMode currentPendingUnsavedConversationRenderMode,
            @NonNull Runnable syncTogglePreviewMenuSelection,
            @NonNull Consumer<AssistantRenderMode> setPendingUnsavedConversationRenderMode
    ) {

        AssistantRenderModeChangeCoordinator.ApplyResult applyResult = changeApplyAction.apply(
                currentConversationId,
                mode,
                currentPendingUnsavedConversationRenderMode
        );

        return uiApplyAction.apply(
                applyResult,
                syncTogglePreviewMenuSelection,
                setPendingUnsavedConversationRenderMode
        );
    }

    @FunctionalInterface
    interface ChangeApplyAction {
        AssistantRenderModeChangeCoordinator.ApplyResult apply(
                UUID currentConversationId,
                AssistantRenderMode mode,
                AssistantRenderMode currentPendingUnsavedConversationRenderMode
        );
    }

    @FunctionalInterface
    interface UiApplyAction {
        boolean apply(
                AssistantRenderModeChangeCoordinator.ApplyResult applyResult,
                Runnable syncTogglePreviewMenuSelection,
                Consumer<AssistantRenderMode> setPendingUnsavedConversationRenderMode
        );
    }
}
