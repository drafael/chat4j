package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.RenderMode;
import lombok.NonNull;

public class GeneralSettingsApplyCoordinator {

    private final SettingsResolver settingsResolver;
    private final ModeResolver modeResolver;

    public GeneralSettingsApplyCoordinator(
            GeneralSettingsResolver generalSettingsResolver,
            RenderModeSelectionResolver renderModeSelectionResolver
    ) {
        this(generalSettingsResolver::resolve, renderModeSelectionResolver::resolve);
    }

    GeneralSettingsApplyCoordinator(@NonNull SettingsResolver settingsResolver, @NonNull ModeResolver modeResolver) {
        this.settingsResolver = settingsResolver;
        this.modeResolver = modeResolver;
    }

    public ApplyResult apply(boolean isMacOs) {
        GeneralSettingsResolver.GeneralSettings generalSettings = settingsResolver.resolve(isMacOs);
        RenderMode modeToApply = modeResolver.resolve(generalSettings.defaultRenderMode());

        return new ApplyResult(
                generalSettings.sendOnEnter(),
                generalSettings.autoScrollEnabled(),
                generalSettings.defaultRenderMode(),
                modeToApply,
                generalSettings.menuBarEnabled()
        );
    }

    public record ApplyResult(
            boolean sendOnEnter,
            boolean autoScrollEnabled,
            RenderMode defaultRenderMode,
            RenderMode modeToApply,
            boolean menuBarEnabled
    ) {
    }

    @FunctionalInterface
    interface SettingsResolver {
        GeneralSettingsResolver.GeneralSettings resolve(boolean isMacOs);
    }

    @FunctionalInterface
    interface ModeResolver {
        RenderMode resolve(RenderMode defaultMode);
    }
}
