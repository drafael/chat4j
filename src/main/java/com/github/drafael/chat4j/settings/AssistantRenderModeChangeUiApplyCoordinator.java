package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import lombok.NonNull;

import java.util.function.Consumer;

public class AssistantRenderModeChangeUiApplyCoordinator {

    public boolean apply(
            @NonNull AssistantRenderModeChangeCoordinator.ApplyResult applyResult,
            @NonNull Runnable syncTogglePreviewMenuSelection,
            @NonNull Consumer<AssistantRenderMode> setPendingUnsavedConversationRenderMode
    ) {

        if (!applyResult.handled()) {
            return false;
        }

        syncTogglePreviewMenuSelection.run();
        setPendingUnsavedConversationRenderMode.accept(applyResult.pendingUnsavedConversationRenderMode());
        return true;
    }
}
