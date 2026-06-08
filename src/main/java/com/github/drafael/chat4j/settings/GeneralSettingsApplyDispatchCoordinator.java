package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.render.RenderMode;
import lombok.NonNull;
import org.apache.commons.lang3.Validate;

public class GeneralSettingsApplyDispatchCoordinator {

    private final SettingsApplyAction settingsApplyAction;
    private final UiApplyAction uiApplyAction;

    public GeneralSettingsApplyDispatchCoordinator(
            GeneralSettingsApplyCoordinator generalSettingsApplyCoordinator,
            GeneralSettingsUiApplyCoordinator generalSettingsUiApplyCoordinator
    ) {
        this(generalSettingsApplyCoordinator::apply, generalSettingsUiApplyCoordinator::apply);
    }

    GeneralSettingsApplyDispatchCoordinator(@NonNull SettingsApplyAction settingsApplyAction, @NonNull UiApplyAction uiApplyAction) {
        this.settingsApplyAction = settingsApplyAction;
        this.uiApplyAction = uiApplyAction;
    }

    public RenderMode apply(
            boolean isMacOS,
            @NonNull GeneralSettingsUiApplyCoordinator.SendOnEnterApplier sendOnEnterApplier,
            @NonNull GeneralSettingsUiApplyCoordinator.AutoScrollApplier autoScrollApplier,
            @NonNull GeneralSettingsUiApplyCoordinator.RenderModeApplier renderModeApplier,
            @NonNull GeneralSettingsUiApplyCoordinator.MenuBarSettingApplier menuBarSettingApplier
    ) {

        GeneralSettingsApplyCoordinator.ApplyResult applyResult = Validate.notNull(
                settingsApplyAction.apply(isMacOS),
                "applyResult must not be null"
        );

        return uiApplyAction.apply(
                applyResult,
                sendOnEnterApplier,
                autoScrollApplier,
                renderModeApplier,
                menuBarSettingApplier
        );
    }

    @FunctionalInterface
    interface SettingsApplyAction {
        GeneralSettingsApplyCoordinator.ApplyResult apply(boolean isMacOS);
    }

    @FunctionalInterface
    interface UiApplyAction {
        RenderMode apply(
                GeneralSettingsApplyCoordinator.ApplyResult applyResult,
                GeneralSettingsUiApplyCoordinator.SendOnEnterApplier sendOnEnterApplier,
                GeneralSettingsUiApplyCoordinator.AutoScrollApplier autoScrollApplier,
                GeneralSettingsUiApplyCoordinator.RenderModeApplier renderModeApplier,
                GeneralSettingsUiApplyCoordinator.MenuBarSettingApplier menuBarSettingApplier
        );
    }
}
