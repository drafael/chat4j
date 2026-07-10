package com.github.drafael.chat4j.web;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import lombok.NonNull;

public final class WebSearchSettings {

    private static final String KEY_AUTO_BROWSE_TOP_N = "chat4j.web.autoBrowseTopN";
    private static final int DEFAULT_AUTO_BROWSE_TOP_N = 3;

    private final SettingsRepository settingsRepo;

    public WebSearchSettings(@NonNull SettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    public int autoBrowseTopN() {
        return Integer.parseInt(settingsRepo.get(KEY_AUTO_BROWSE_TOP_N, String.valueOf(DEFAULT_AUTO_BROWSE_TOP_N)));
    }

    public void persistAutoBrowseTopN(int topN) {
        settingsRepo.put(KEY_AUTO_BROWSE_TOP_N, String.valueOf(topN));
    }
}
