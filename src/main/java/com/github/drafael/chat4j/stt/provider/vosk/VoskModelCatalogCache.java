package com.github.drafael.chat4j.stt.provider.vosk;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.time.Instant;
import java.util.Optional;

public class VoskModelCatalogCache {

    private static final String JSON_KEY = SettingsKeys.STT_PREFIX + "catalog.vosk.rawJson";
    private static final String UPDATED_KEY = SettingsKeys.STT_PREFIX + "catalog.vosk.rawJson.updatedAt";

    private final SettingsRepository settingsRepo;

    public VoskModelCatalogCache(SettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    public Optional<String> rawJson() {
        return settingsRepo.get(JSON_KEY);
    }

    public void saveRawJson(String json) {
        settingsRepo.updateBatch(batch -> {
            batch.put(JSON_KEY, json);
            batch.put(UPDATED_KEY, Instant.now().toString());
        });
    }
}
