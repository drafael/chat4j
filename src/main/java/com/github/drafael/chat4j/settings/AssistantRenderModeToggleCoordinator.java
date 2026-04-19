package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import org.apache.commons.lang3.Validate;

public class AssistantRenderModeToggleCoordinator {

    public boolean apply(boolean previewSelected, boolean syncingPreviewMenuSelection, RenderModeApplier renderModeApplier) {
        Validate.notNull(renderModeApplier, "renderModeApplier must not be null");

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
