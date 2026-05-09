package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import lombok.NonNull;

public class GeneralSettingsUiApplyCoordinator {

    public AssistantRenderMode apply(
            @NonNull GeneralSettingsApplyCoordinator.ApplyResult applyResult,
            @NonNull SendOnEnterApplier sendOnEnterApplier,
            @NonNull AutoScrollApplier autoScrollApplier,
            @NonNull RenderModeApplier renderModeApplier,
            @NonNull MenuBarSettingApplier menuBarSettingApplier
    ) {

        sendOnEnterApplier.apply(applyResult.sendOnEnter());
        autoScrollApplier.apply(applyResult.autoScrollEnabled());
        renderModeApplier.apply(applyResult.modeToApply(), true);
        menuBarSettingApplier.apply(applyResult.menuBarEnabled());
        return applyResult.defaultAssistantRenderMode();
    }

    @FunctionalInterface
    public interface SendOnEnterApplier {
        void apply(boolean sendOnEnter);
    }

    @FunctionalInterface
    public interface AutoScrollApplier {
        void apply(boolean autoScrollEnabled);
    }

    @FunctionalInterface
    public interface RenderModeApplier {
        void apply(AssistantRenderMode mode, boolean userInitiated);
    }

    @FunctionalInterface
    public interface MenuBarSettingApplier {
        void apply(boolean enabled);
    }
}
