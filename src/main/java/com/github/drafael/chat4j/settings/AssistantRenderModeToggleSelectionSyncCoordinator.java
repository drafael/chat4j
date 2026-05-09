package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import lombok.NonNull;

import javax.swing.JCheckBoxMenuItem;
import java.util.function.Consumer;

public class AssistantRenderModeToggleSelectionSyncCoordinator {

    public boolean sync(
            JCheckBoxMenuItem togglePreviewMenuItem,
            @NonNull AssistantRenderMode currentAssistantRenderMode,
            @NonNull Consumer<Boolean> setSyncingPreviewMenuSelection
    ) {

        if (togglePreviewMenuItem == null) {
            return false;
        }

        setSyncingPreviewMenuSelection.accept(true);
        try {
            togglePreviewMenuItem.setSelected(currentAssistantRenderMode == AssistantRenderMode.PREVIEW);
        } finally {
            setSyncingPreviewMenuSelection.accept(false);
        }

        return true;
    }
}
