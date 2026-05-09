package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import lombok.NonNull;

public class AssistantRenderModeToggleCoordinator {

    public boolean apply(
        boolean previewSelected,
        boolean syncingPreviewMenuSelection,
        @NonNull RenderModeApplier renderModeApplier
) {

        if (syncingPreviewMenuSelection) {
            return false;
        }

        AssistantRenderMode mode = previewSelected
                ? AssistantRenderMode.PREVIEW
                : AssistantRenderMode.MARKDOWN;
        renderModeApplier.apply(mode, true);
        return true;
    }

    @FunctionalInterface
    public interface RenderModeApplier {
        void apply(AssistantRenderMode mode, boolean userInitiated);
    }
}
