package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.RenderMode;
import lombok.NonNull;

public class RenderModeChangeDispatchCoordinator {

    private final ChangeApplyAction changeApplyAction;
    private final UiApplyAction uiApplyAction;

    public RenderModeChangeDispatchCoordinator(
            RenderModeChangeCoordinator renderModeChangeCoordinator,
            RenderModeChangeUiApplyCoordinator renderModeChangeUiApplyCoordinator
    ) {
        this(renderModeChangeCoordinator::apply, renderModeChangeUiApplyCoordinator::apply);
    }

    RenderModeChangeDispatchCoordinator(@NonNull ChangeApplyAction changeApplyAction, @NonNull UiApplyAction uiApplyAction) {
        this.changeApplyAction = changeApplyAction;
        this.uiApplyAction = uiApplyAction;
    }

    public boolean apply(
            @NonNull RenderMode mode,
            @NonNull Runnable syncTogglePreviewMenuSelection
    ) {

        RenderModeChangeCoordinator.ApplyResult applyResult = changeApplyAction.apply(mode);
        return uiApplyAction.apply(applyResult, syncTogglePreviewMenuSelection);
    }

    @FunctionalInterface
    interface ChangeApplyAction {
        RenderModeChangeCoordinator.ApplyResult apply(RenderMode mode);
    }

    @FunctionalInterface
    interface UiApplyAction {
        boolean apply(RenderModeChangeCoordinator.ApplyResult applyResult, Runnable syncTogglePreviewMenuSelection);
    }
}
