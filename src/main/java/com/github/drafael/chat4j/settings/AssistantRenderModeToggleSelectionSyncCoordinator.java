package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import org.apache.commons.lang3.Validate;

import javax.swing.JCheckBoxMenuItem;
import java.util.function.Consumer;

public class AssistantRenderModeToggleSelectionSyncCoordinator {

    public boolean sync(
            JCheckBoxMenuItem togglePreviewMenuItem,
            AssistantRenderMode currentAssistantRenderMode,
            Consumer<Boolean> setSyncingPreviewMenuSelection
    ) {
        Validate.notNull(currentAssistantRenderMode, "currentAssistantRenderMode must not be null");
        Validate.notNull(
                setSyncingPreviewMenuSelection,
                "setSyncingPreviewMenuSelection must not be null"
        );

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
