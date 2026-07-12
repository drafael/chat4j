package com.github.drafael.chat4j.stt.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.provider.assemblyai.AssemblyAiSpeechToTextProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;

public class SpeechToTextCatalogStore {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration STALE_AFTER = Duration.ofHours(24);

    private final SettingsRepository settingsRepo;

    public SpeechToTextCatalogStore(SettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    public List<SpeechToTextCatalogItem> models(SpeechToTextProvider provider, SpeechToTextCatalogItem selected) {
        if (AssemblyAiSpeechToTextProvider.ID.equals(provider.id())) {
            return provider.bundledModels().stream()
                    .filter(item -> AssemblyAiSpeechToTextProvider.isBundledModelId(item.id()))
                    .toList();
        }
        return mergeWithSelected(cachedModels(provider.id()), provider.bundledModels(), selected);
    }

    public List<SpeechToTextCatalogItem> cachedModels(String providerId) {
        return settingsRepo.get(settings(providerId).catalogModelsKey())
                .map(this::deserialize)
                .orElse(emptyList());
    }

    public boolean stale(String providerId) {
        return settingsRepo.get(settings(providerId).catalogUpdatedAtKey())
                .map(this::isStaleTimestamp)
                .orElse(true);
    }

    public void invalidate(String providerId) {
        SpeechToTextProviderSettings providerSettings = settings(providerId);
        settingsRepo.updateBatch(batch -> {
            batch.remove(providerSettings.catalogModelsKey());
            batch.remove(providerSettings.catalogUpdatedAtKey());
        });
        providerSettings.clearModel();
    }

    public void saveModels(String providerId, List<SpeechToTextCatalogItem> models) throws Exception {
        saveModelsIf(providerId, models, () -> true);
    }

    public boolean saveModelsIf(String providerId, List<SpeechToTextCatalogItem> models, BooleanSupplier condition) throws Exception {
        SpeechToTextProviderSettings providerSettings = settings(providerId);
        String modelsJson = OBJECT_MAPPER.writeValueAsString(models == null ? emptyList() : models);
        return settingsRepo.updateBatchIf(condition, batch -> {
            batch.put(providerSettings.catalogModelsKey(), modelsJson);
            batch.put(providerSettings.catalogUpdatedAtKey(), Instant.now().toString());
        });
    }

    public List<SpeechToTextCatalogItem> mergeWithSelected(
            List<SpeechToTextCatalogItem> cached,
            List<SpeechToTextCatalogItem> bundled,
            SpeechToTextCatalogItem selected
    ) {
        Map<String, SpeechToTextCatalogItem> merged = new LinkedHashMap<>();
        addAll(merged, bundled);
        addAll(merged, cached);
        if (selected != null && StringUtils.isNotBlank(selected.id())) {
            merged.putIfAbsent(selected.id(), selected);
        }
        return merged.values().stream().toList();
    }

    private List<SpeechToTextCatalogItem> deserialize(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return emptyList();
        }
    }

    private boolean isStaleTimestamp(String value) {
        try {
            return Instant.parse(value).plus(STALE_AFTER).isBefore(Instant.now());
        } catch (Exception e) {
            return true;
        }
    }

    private SpeechToTextProviderSettings settings(String providerId) {
        return SpeechToTextProviderSettingsFactory.forProvider(settingsRepo, providerId);
    }

    private static void addAll(Map<String, SpeechToTextCatalogItem> target, List<SpeechToTextCatalogItem> items) {
        if (items == null) {
            return;
        }
        items.stream()
                .filter(item -> item != null && StringUtils.isNotBlank(item.id()))
                .forEach(item -> target.putIfAbsent(item.id(), item));
    }
}
