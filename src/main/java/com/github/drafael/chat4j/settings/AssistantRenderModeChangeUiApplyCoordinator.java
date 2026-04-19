package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import org.apache.commons.lang3.Validate;

import java.util.function.Consumer;

public class AssistantRenderModeChangeUiApplyCoordinator {

    public boolean apply(
            AssistantRenderModeChangeCoordinator.ApplyResult applyResult,
            Runnable syncTogglePreviewMenuSelection,
            Consumer<AssistantRenderMode> setPendingUnsavedConversationRenderMode
    ) {
        Validate.notNull(applyResult, "applyResult must not be null");
        Validate.notNull(syncTogglePreviewMenuSelection, "syncTogglePreviewMenuSelection must not be null");
        Validate.notNull(
                setPendingUnsavedConversationRenderMode,
                "setPendingUnsavedConversationRenderMode must not be null"
        );

        if (!applyResult.handled()) {
            return false;
        }

        syncTogglePreviewMenuSelection.run();
        setPendingUnsavedConversationRenderMode.accept(applyResult.pendingUnsavedConversationRenderMode());
        return true;
    }
}
