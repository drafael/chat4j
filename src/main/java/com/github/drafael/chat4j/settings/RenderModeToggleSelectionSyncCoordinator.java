package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.render.RenderMode;
import lombok.NonNull;

import javax.swing.JCheckBoxMenuItem;
import java.util.function.Consumer;

public class RenderModeToggleSelectionSyncCoordinator {

    public boolean sync(
            JCheckBoxMenuItem togglePreviewMenuItem,
            @NonNull RenderMode currentRenderMode,
            @NonNull Consumer<Boolean> setSyncingPreviewMenuSelection
    ) {

        if (togglePreviewMenuItem == null) {
            return false;
        }

        setSyncingPreviewMenuSelection.accept(true);
        try {
            togglePreviewMenuItem.setSelected(currentRenderMode == RenderMode.PREVIEW);
        } finally {
            setSyncingPreviewMenuSelection.accept(false);
        }

        return true;
    }
}
