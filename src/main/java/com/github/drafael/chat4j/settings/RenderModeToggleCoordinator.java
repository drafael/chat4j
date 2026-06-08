package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.render.RenderMode;
import lombok.NonNull;

public class RenderModeToggleCoordinator {

    public boolean apply(
        boolean previewSelected,
        boolean syncingPreviewMenuSelection,
        @NonNull RenderModeApplier renderModeApplier
) {

        if (syncingPreviewMenuSelection) {
            return false;
        }

        RenderMode mode = previewSelected
                ? RenderMode.PREVIEW
                : RenderMode.MARKDOWN;
        renderModeApplier.apply(mode, true);
        return true;
    }

    @FunctionalInterface
    public interface RenderModeApplier {
        void apply(RenderMode mode, boolean userInitiated);
    }
}
