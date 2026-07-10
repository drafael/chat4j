package com.github.drafael.chat4j.stt.provider.vosk;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.util.Optional;

public class VoskModelCatalogCache {

    private final VoskSpeechToTextSettings settings;

    public VoskModelCatalogCache(SettingsRepository settingsRepo) {
        this.settings = new VoskSpeechToTextSettings(settingsRepo);
    }

    public Optional<String> rawJson() {
        return settings.rawCatalogJson();
    }

    public void saveRawJson(String json) {
        settings.saveRawCatalogJson(json);
    }
}
