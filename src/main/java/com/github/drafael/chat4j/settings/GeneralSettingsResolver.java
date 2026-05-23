package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.RenderMode;
import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import lombok.NonNull;

public class GeneralSettingsResolver {

    private static final String KEY_MENU_BAR_ENABLED = SettingsKeys.MENU_BAR_ENABLED;

    private final SettingsRepo settingsRepo;
    private final RenderModeSettingsCoordinator renderModeSettingsCoordinator;

    public GeneralSettingsResolver(
            @NonNull SettingsRepo settingsRepo,
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
