package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import lombok.NonNull;

public class RenderModeSettingsCoordinator {

    private static final String KEY_RENDER_MODE_DEFAULT = SettingsKeys.CHAT_RENDER_MODE;

    private final SettingsRepo settingsRepo;

    public RenderModeSettingsCoordinator(@NonNull SettingsRepo settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    public RenderMode resolveDefaultMode() {
        try {
            String stored = settingsRepo.get(
                    KEY_RENDER_MODE_DEFAULT,
                    RenderMode.PREVIEW.settingValue()
            );
            return toRenderMode(stored);
        } catch (Exception e) {
            return RenderMode.PREVIEW;
        }
    }

    public void persistDefaultMode(RenderMode mode) {
        if (mode == null) {
            return;
        }

        try {
            settingsRepo.put(KEY_RENDER_MODE_DEFAULT, toSettingValue(mode));
        } catch (Exception ignored) {
            // Render mode persistence is best-effort.
        }
    }

    private RenderMode toRenderMode(String stored) {
        return RenderMode.fromSettingValue(stored);
    }

    private String toSettingValue(RenderMode mode) {
        return mode.settingValue();
    }
}
