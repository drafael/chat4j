package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import lombok.NonNull;

public class GeneralSettingsResolver {

    private static final String KEY_MENU_BAR_ENABLED = SettingsKeys.MENU_BAR_ENABLED;

    private final SettingsRepository settingsRepo;
    private final RenderModeSettingsCoordinator renderModeSettingsCoordinator;

    public GeneralSettingsResolver(
            @NonNull SettingsRepository settingsRepo,
            @NonNull RenderModeSettingsCoordinator renderModeSettingsCoordinator
    ) {
        this.settingsRepo = settingsRepo;
        this.renderModeSettingsCoordinator = renderModeSettingsCoordinator;
    }

    public GeneralSettings resolve(boolean defaultMenuBarEnabled) {
        try {
            String sendKey = settingsRepo.get(SettingsKeys.CHAT_SEND_KEY, "Enter");
            boolean sendOnEnter = !"Ctrl+Enter".equalsIgnoreCase(sendKey);
            boolean autoScrollEnabled = Boolean.parseBoolean(settingsRepo.get(SettingsKeys.CHAT_AUTO_SCROLL, "true"));
            RenderMode defaultRenderMode = renderModeSettingsCoordinator.resolveDefaultMode();
            boolean menuBarEnabled = Boolean.parseBoolean(
                    settingsRepo.get(KEY_MENU_BAR_ENABLED, String.valueOf(defaultMenuBarEnabled))
            );

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
