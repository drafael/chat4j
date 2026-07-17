package com.github.drafael.chat4j.tts.provider;

import com.github.drafael.chat4j.persistence.settings.SettingsKeySlugs;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import lombok.NonNull;

public abstract class AbstractTextToSpeechProviderSettings implements TextToSpeechProviderSettings {

    private static final String PREFIX = "chat4j.tts.";

    protected final SettingsRepository settingsRepo;
    private final String providerId;

    protected AbstractTextToSpeechProviderSettings(@NonNull SettingsRepository settingsRepo, String providerId) {
        this.settingsRepo = settingsRepo;
        this.providerId = providerId;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public TextToSpeechCatalogItem selectedModel(TextToSpeechCatalogItem fallback) {
        return selectedItem(modelIdKey(), modelLabelKey(), fallback);
    }

    @Override
    public TextToSpeechCatalogItem selectedVoice(TextToSpeechCatalogItem fallback) {
        return selectedItem(voiceIdKey(), voiceLabelKey(), fallback);
    }

    @Override
    public void saveModel(TextToSpeechCatalogItem model) {
        saveItem(modelIdKey(), modelLabelKey(), model);
    }

    @Override
    public void saveVoice(TextToSpeechCatalogItem voice) {
        saveItem(voiceIdKey(), voiceLabelKey(), voice);
    }

    private TextToSpeechCatalogItem selectedItem(String idKey, String labelKey, TextToSpeechCatalogItem fallback) {
        String id = settingsRepo.get(idKey, fallback.id());
        String label = settingsRepo.get(labelKey, fallback.label());
        return new TextToSpeechCatalogItem(id, label, fallback.description());
    }

    private void saveItem(String idKey, String labelKey, TextToSpeechCatalogItem item) {
        settingsRepo.updateBatch(batch -> {
            batch.put(idKey, item.id());
            batch.put(labelKey, item.label());
        });
    }

    private String modelIdKey() {
        return "%s%s.model.id".formatted(PREFIX, providerSlug());
    }

    private String modelLabelKey() {
        return "%s%s.model.label".formatted(PREFIX, providerSlug());
    }

    private String voiceIdKey() {
        return "%s%s.voice.id".formatted(PREFIX, providerSlug());
    }

    private String voiceLabelKey() {
        return "%s%s.voice.label".formatted(PREFIX, providerSlug());
    }

    protected String providerSlug() {
        return SettingsKeySlugs.providerSlug(providerId);
    }
}
