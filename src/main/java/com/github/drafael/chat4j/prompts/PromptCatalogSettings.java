package com.github.drafael.chat4j.prompts;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.util.Optional;
import lombok.NonNull;

public final class PromptCatalogSettings {

    private static final String KEY_PROMPT_CATALOG = "chat4j.prompts.catalog";

    private final SettingsRepository settingsRepo;

    public PromptCatalogSettings(@NonNull SettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    public Optional<String> loadCatalogJson() {
        return settingsRepo.get(KEY_PROMPT_CATALOG);
    }

    public void saveCatalogJson(String json) {
        settingsRepo.put(KEY_PROMPT_CATALOG, json);
    }
}
