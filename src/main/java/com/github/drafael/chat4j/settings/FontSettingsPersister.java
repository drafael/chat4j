package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.sql.SQLException;
import lombok.NonNull;
import org.apache.commons.lang3.Validate;

public class FontSettingsPersister {

    private final SettingsRepository settingsRepo;

    public FontSettingsPersister(@NonNull SettingsRepository settingsRepo) {
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
