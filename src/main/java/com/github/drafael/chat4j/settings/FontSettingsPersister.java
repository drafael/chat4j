package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.storage.SettingsRepo;
import lombok.NonNull;
import org.apache.commons.lang3.Validate;

import java.sql.SQLException;

public class FontSettingsPersister {

    private final SettingsRepo settingsRepo;

    public FontSettingsPersister(@NonNull SettingsRepo settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    public void persistAppFontSelection(String family, int size) throws SQLException {
        Validate.notBlank(family, "family must not be blank");

        settingsRepo.put(AppearancePanel.KEY_APP_FONT, family);
        settingsRepo.put(AppearancePanel.KEY_APP_FONT_SIZE, String.valueOf(size));
    }

    public void persistCodeFontFamily(String family) throws SQLException {
        Validate.notBlank(family, "family must not be blank");

        settingsRepo.put(AppearancePanel.KEY_CODE_FONT, family);
    }
}
