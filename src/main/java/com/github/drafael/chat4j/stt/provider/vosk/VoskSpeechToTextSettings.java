package com.github.drafael.chat4j.stt.provider.vosk;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.provider.AbstractSpeechToTextProviderSettings;
import java.nio.file.Path;

public class VoskSpeechToTextSettings extends AbstractSpeechToTextProviderSettings {

    private static final String PREFIX = "chat4j.stt.";
    private static final String FINGERPRINT_KEY = PREFIX + "vosk.model.fingerprint";
    private static final String ROOT_KEY = PREFIX + "vosk.model.root";

    public VoskSpeechToTextSettings(SettingsRepository settingsRepo) {
        super(settingsRepo, VoskSpeechToTextProvider.ID);
    }

    public void saveSelectedModel(VoskInstalledModel model, Path root) {
        settingsRepo.updateBatch(batch -> {
            batch.put(modelIdKey(), model.id());
            batch.put(modelLabelKey(), model.label());
            batch.put(FINGERPRINT_KEY, model.fingerprint());
            batch.put(ROOT_KEY, root.toString());
        });
    }

    public void clearSelectedModel() {
        settingsRepo.updateBatch(batch -> {
            batch.remove(modelIdKey());
            batch.remove(modelLabelKey());
            batch.remove(FINGERPRINT_KEY);
            batch.remove(ROOT_KEY);
        });
    }

    public String savedRoot() {
        return settingsRepo.get(ROOT_KEY, "");
    }

    public String savedFingerprint() {
        return settingsRepo.get(FINGERPRINT_KEY, "");
    }

}
