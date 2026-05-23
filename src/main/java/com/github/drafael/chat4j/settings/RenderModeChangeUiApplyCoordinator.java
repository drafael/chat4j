package com.github.drafael.chat4j.settings;

import lombok.NonNull;

public class RenderModeChangeUiApplyCoordinator {

    public boolean apply(
            @NonNull RenderModeChangeCoordinator.ApplyResult applyResult,
            @NonNull Runnable syncTogglePreviewMenuSelection
    ) {

        if (!applyResult.handled()) {
            return false;
        }

        syncTogglePreviewMenuSelection.run();
        return true;
    }
}
