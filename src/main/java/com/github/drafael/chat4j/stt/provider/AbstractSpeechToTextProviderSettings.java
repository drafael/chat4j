package com.github.drafael.chat4j.stt.provider;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

public abstract class AbstractSpeechToTextProviderSettings implements SpeechToTextProviderSettings {

    private static final String PREFIX = "chat4j.stt.";

    protected final SettingsRepository settingsRepo;
    private final String providerId;

    protected AbstractSpeechToTextProviderSettings(@NonNull SettingsRepository settingsRepo, String providerId) {
        this.settingsRepo = settingsRepo;
        this.providerId = providerId;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public SpeechToTextCatalogItem selectedModel(SpeechToTextCatalogItem fallback) {
        if (fallback == null) {
            return null;
        }
        String id = StringUtils.defaultIfBlank(settingsRepo.get(modelIdKey(), fallback.id()), fallback.id());
        String label = settingsRepo.get(modelLabelKey(), fallback.label());
        return new SpeechToTextCatalogItem(id, label, fallback.description());
    }

    @Override
    public void saveModel(SpeechToTextCatalogItem model) {
        settingsRepo.updateBatch(batch -> {
            batch.put(modelIdKey(), model.id());
            batch.put(modelLabelKey(), model.label());
        });
    }

    @Override
    public void clearModel() {
        settingsRepo.updateBatch(batch -> {
            batch.remove(modelIdKey());
            batch.remove(modelLabelKey());
        });
    }

    @Override
    public String selectedModelId() {
        return settingsRepo.get(modelIdKey(), "");
    }

    @Override
    public String catalogModelsKey() {
        return "%scatalog.%s.models".formatted(PREFIX, providerSlug());
    }

    @Override
    public String catalogUpdatedAtKey() {
        return "%scatalog.%s.updatedAt".formatted(PREFIX, providerSlug());
    }

    protected String modelIdKey() {
        return "%s%s.model.id".formatted(PREFIX, providerSlug());
    }

    protected String modelLabelKey() {
        return "%s%s.model.label".formatted(PREFIX, providerSlug());
    }

    protected String providerSlug() {
        return SettingsKeys.providerSlug(providerId);
    }
}
