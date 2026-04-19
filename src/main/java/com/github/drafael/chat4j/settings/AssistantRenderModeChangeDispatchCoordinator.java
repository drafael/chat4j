package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import org.apache.commons.lang3.Validate;

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

    AssistantRenderModeChangeDispatchCoordinator(ChangeApplyAction changeApplyAction, UiApplyAction uiApplyAction) {
        this.changeApplyAction = Validate.notNull(changeApplyAction, "changeApplyAction must not be null");
        this.uiApplyAction = Validate.notNull(uiApplyAction, "uiApplyAction must not be null");
    }

    public boolean apply(
            UUID currentConversationId,
            AssistantRenderMode mode,
            AssistantRenderMode currentPendingUnsavedConversationRenderMode,
            Runnable syncTogglePreviewMenuSelection,
            Consumer<AssistantRenderMode> setPendingUnsavedConversationRenderMode
    ) {
        Validate.notNull(mode, "mode must not be null");
        Validate.notNull(syncTogglePreviewMenuSelection, "syncTogglePreviewMenuSelection must not be null");
        Validate.notNull(
                setPendingUnsavedConversationRenderMode,
                "setPendingUnsavedConversationRenderMode must not be null"
        );

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
