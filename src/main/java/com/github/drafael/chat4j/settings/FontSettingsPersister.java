package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import lombok.NonNull;
import org.apache.commons.lang3.Validate;

public class FontSettingsPersister {

    private final SettingsRepository settingsRepo;

    public FontSettingsPersister(@NonNull SettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    public void persistAppFontSelection(String family, int size) {
        Validate.notBlank(family, "family must not be blank");

        fontSettings().persistAppFontSelection(family, size);
    }

    public void persistCodeFontFamily(String family) {
        Validate.notBlank(family, "family must not be blank");

        fontSettings().persistCodeFontFamily(family);
    }

    private FontSettings fontSettings() {
        return new FontSettings(settingsRepo);
    }
}
