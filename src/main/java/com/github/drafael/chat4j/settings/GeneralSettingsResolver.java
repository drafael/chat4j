package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import org.apache.commons.lang3.Validate;

public class GeneralSettingsResolver {

    private static final String KEY_MENU_BAR_ENABLED = SettingsKeys.MENU_BAR_ENABLED;

    private final SettingsRepo settingsRepo;
    private final AssistantRenderModeSettingsCoordinator assistantRenderModeSettingsCoordinator;

    public GeneralSettingsResolver(
            SettingsRepo settingsRepo,
            AssistantRenderModeSettingsCoordinator assistantRenderModeSettingsCoordinator
    ) {
        this.settingsRepo = Validate.notNull(settingsRepo, "settingsRepo must not be null");
        this.assistantRenderModeSettingsCoordinator = Validate.notNull(
                assistantRenderModeSettingsCoordinator,
                "assistantRenderModeSettingsCoordinator must not be null"
        );
    }

    public GeneralSettings resolve(boolean defaultMenuBarEnabled) {
        try {
            String sendKey = settingsRepo.get(SettingsKeys.CHAT_SEND_KEY, "Enter");
            boolean sendOnEnter = !"Ctrl+Enter".equalsIgnoreCase(sendKey);
            boolean autoScrollEnabled = Boolean.parseBoolean(settingsRepo.get(SettingsKeys.CHAT_AUTO_SCROLL, "true"));
            AssistantRenderMode defaultAssistantRenderMode = assistantRenderModeSettingsCoordinator.resolveDefaultMode();
            boolean menuBarEnabled = Boolean.parseBoolean(
                    settingsRepo.get(KEY_MENU_BAR_ENABLED, String.valueOf(defaultMenuBarEnabled))
            );

            return new GeneralSettings(sendOnEnter, autoScrollEnabled, defaultAssistantRenderMode, menuBarEnabled);
        } catch (Exception e) {
            return new GeneralSettings(true, true, AssistantRenderMode.PREVIEW, defaultMenuBarEnabled);
        }
    }

    public record GeneralSettings(
            boolean sendOnEnter,
            boolean autoScrollEnabled,
            AssistantRenderMode defaultAssistantRenderMode,
            boolean menuBarEnabled
    ) {
    }
}
