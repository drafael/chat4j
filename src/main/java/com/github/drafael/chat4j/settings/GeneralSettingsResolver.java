package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import lombok.NonNull;

public class GeneralSettingsResolver {

    private final ChatBehaviorSettings chatBehaviorSettings;
    private final RenderModeSettings renderModeSettings;

    public GeneralSettingsResolver(
            @NonNull SettingsRepository settingsRepo,
            @NonNull RenderModeSettings renderModeSettings
    ) {
        this(new ChatBehaviorSettings(settingsRepo), renderModeSettings);
    }

    GeneralSettingsResolver(
            @NonNull ChatBehaviorSettings chatBehaviorSettings,
            @NonNull RenderModeSettings renderModeSettings
    ) {
        this.chatBehaviorSettings = chatBehaviorSettings;
        this.renderModeSettings = renderModeSettings;
    }

    public GeneralSettings resolve(boolean defaultMenuBarEnabled) {
        try {
            boolean sendOnEnter = chatBehaviorSettings.sendOnEnter();
            boolean autoScrollEnabled = chatBehaviorSettings.autoScrollEnabled();
            boolean menuBarEnabled = chatBehaviorSettings.menuBarEnabled(defaultMenuBarEnabled);
            RenderMode defaultRenderMode = renderModeSettings.resolveDefaultMode();

            return new GeneralSettings(sendOnEnter, autoScrollEnabled, defaultRenderMode, menuBarEnabled);
        } catch (Exception e) {
            return new GeneralSettings(true, true, RenderMode.PREVIEW, defaultMenuBarEnabled);
        }
    }

    public record GeneralSettings(
            boolean sendOnEnter,
            boolean autoScrollEnabled,
            RenderMode defaultRenderMode,
            boolean menuBarEnabled
    ) {
    }
}
