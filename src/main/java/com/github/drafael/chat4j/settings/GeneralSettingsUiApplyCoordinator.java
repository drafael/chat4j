package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import org.apache.commons.lang3.Validate;

public class GeneralSettingsUiApplyCoordinator {

    public AssistantRenderMode apply(
            GeneralSettingsApplyCoordinator.ApplyResult applyResult,
            SendOnEnterApplier sendOnEnterApplier,
            AutoScrollApplier autoScrollApplier,
            RenderModeApplier renderModeApplier,
            MenuBarSettingApplier menuBarSettingApplier
    ) {
        Validate.notNull(applyResult, "applyResult must not be null");
        Validate.notNull(sendOnEnterApplier, "sendOnEnterApplier must not be null");
        Validate.notNull(autoScrollApplier, "autoScrollApplier must not be null");
        Validate.notNull(renderModeApplier, "renderModeApplier must not be null");
        Validate.notNull(menuBarSettingApplier, "menuBarSettingApplier must not be null");

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
