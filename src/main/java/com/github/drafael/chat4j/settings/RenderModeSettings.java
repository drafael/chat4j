package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.util.Arrays;
import java.util.Optional;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

public final class RenderModeSettings {

    private static final String KEY_RENDER_MODE_DEFAULT = "chat4j.chat.render.mode";

    private final SettingsRepository settingsRepo;

    public RenderModeSettings(@NonNull SettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    public RenderMode resolveDefaultMode() {
        try {
            return RenderMode.fromSettingValue(readDefaultModeValue());
        } catch (Exception e) {
            return RenderMode.PREVIEW;
        }
    }

    public String readDefaultModeValue() {
        return settingsRepo.get(KEY_RENDER_MODE_DEFAULT, RenderMode.PREVIEW.settingValue());
    }

    public void persistDefaultMode(RenderMode mode) {
        if (mode == null) {
            return;
        }

        persistDefaultModeValue(mode.settingValue());
    }

    public void persistDefaultModeValue(String value) {
        settingsRepo.put(KEY_RENDER_MODE_DEFAULT, value);
    }

    public Optional<RenderMode> parseMode(String value) {
        if (StringUtils.isBlank(value)) {
            return Optional.empty();
        }

        String normalized = value.trim();
        return Arrays.stream(RenderMode.values())
                .filter(mode -> mode.settingValue().equalsIgnoreCase(normalized) || mode.name().equalsIgnoreCase(normalized))
                .findFirst();
    }

    public Optional<String> normalizeSettingValue(String value) {
        return parseMode(value).map(RenderMode::settingValue);
    }
}
