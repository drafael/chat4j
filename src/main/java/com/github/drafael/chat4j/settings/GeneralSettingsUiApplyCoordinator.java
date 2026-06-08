package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.render.RenderMode;
import lombok.NonNull;

public class GeneralSettingsUiApplyCoordinator {

    public RenderMode apply(
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
        return applyResult.defaultRenderMode();
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
        void apply(RenderMode mode, boolean userInitiated);
    }

    @FunctionalInterface
    public interface MenuBarSettingApplier {
        void apply(boolean enabled);
    }
}
