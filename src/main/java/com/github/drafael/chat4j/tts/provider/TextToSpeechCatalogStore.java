package com.github.drafael.chat4j.tts.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static java.util.Collections.emptyList;

public class TextToSpeechCatalogStore {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<TextToSpeechCatalogItem>> ITEM_LIST_TYPE = new TypeReference<>() {
    };

    private final SettingsRepository settingsRepo;

    public TextToSpeechCatalogStore(SettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    public List<TextToSpeechCatalogItem> models(TextToSpeechProvider provider, TextToSpeechCatalogItem selected) {
        return merged(
                normalized(readItems(settings(provider.id()).catalogModelsKey()), provider::normalizeModelSelection),
                provider.bundledModels(),
                normalized(selected, provider::normalizeModelSelection)
        );
    }

    public List<TextToSpeechCatalogItem> voices(TextToSpeechProvider provider, TextToSpeechCatalogItem selected) {
        return merged(
                normalized(readItems(settings(provider.id()).catalogVoicesKey()), provider::normalizeVoiceSelection),
                provider.bundledVoices(),
                normalized(selected, provider::normalizeVoiceSelection)
        );
    }

    public void saveCatalogs(String providerId, List<TextToSpeechCatalogItem> models, List<TextToSpeechCatalogItem> voices) {
        saveCatalogsIf(providerId, models, voices, () -> true);
    }

    public boolean saveCatalogsIf(
            String providerId,
            List<TextToSpeechCatalogItem> models,
            List<TextToSpeechCatalogItem> voices,
            BooleanSupplier condition
    ) {
        TextToSpeechProviderSettings providerSettings = settings(providerId);
        try {
            String modelsJson = OBJECT_MAPPER.writeValueAsString(models == null ? emptyList() : models);
            String voicesJson = OBJECT_MAPPER.writeValueAsString(voices == null ? emptyList() : voices);
            return settingsRepo.updateBatchIf(condition, batch -> {
                batch.put(providerSettings.catalogModelsKey(), modelsJson);
                batch.put(providerSettings.catalogVoicesKey(), voicesJson);
                batch.put(providerSettings.catalogUpdatedAtKey(), Instant.now().toString());
            });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save Text to Speech catalogs", e);
        }
    }

    public void invalidate(String providerId) {
        TextToSpeechProviderSettings providerSettings = settings(providerId);
        settingsRepo.updateBatch(batch -> {
            batch.remove(providerSettings.catalogModelsKey());
            batch.remove(providerSettings.catalogVoicesKey());
            batch.remove(providerSettings.catalogUpdatedAtKey());
        });
        providerSettings.clearModel();
        providerSettings.clearVoice();
    }

    public List<TextToSpeechCatalogItem> mergeWithSelected(
            List<TextToSpeechCatalogItem> discovered,
            List<TextToSpeechCatalogItem> fallback,
            TextToSpeechCatalogItem selected
    ) {
        return merged(discovered, fallback, selected);
    }

    private List<TextToSpeechCatalogItem> readItems(String key) {
        try {
            String json = settingsRepo.get(key, "");
            if (json == null || json.isBlank()) {
                return emptyList();
            }
            return OBJECT_MAPPER.readValue(json, ITEM_LIST_TYPE);
        } catch (Exception e) {
            return emptyList();
        }
    }

    private TextToSpeechProviderSettings settings(String providerId) {
        return TextToSpeechProviderSettingsFactory.forProvider(settingsRepo, providerId);
    }

    private static TextToSpeechCatalogItem normalized(
            TextToSpeechCatalogItem item,
            Function<TextToSpeechCatalogItem, TextToSpeechCatalogItem> normalizer
    ) {
        return item == null ? null : normalizer.apply(item);
    }

    private static List<TextToSpeechCatalogItem> normalized(
            List<TextToSpeechCatalogItem> items,
            Function<TextToSpeechCatalogItem, TextToSpeechCatalogItem> normalizer
    ) {
        if (items == null) {
            return emptyList();
        }
        return items.stream()
                .filter(Objects::nonNull)
                .map(normalizer)
                .filter(Objects::nonNull)
                .toList();
    }

    private static List<TextToSpeechCatalogItem> merged(
            List<TextToSpeechCatalogItem> primary,
            List<TextToSpeechCatalogItem> fallback,
            TextToSpeechCatalogItem selected
    ) {
        Map<String, TextToSpeechCatalogItem> byId = new LinkedHashMap<>();
        List<TextToSpeechCatalogItem> first = primary == null || primary.isEmpty() ? fallback : primary;
        addAll(byId, first);
        if (byId.isEmpty()) {
            addAll(byId, fallback);
        }
        if (selected != null) {
            byId.putIfAbsent(selected.id(), selected);
        }
        return List.copyOf(byId.values());
    }

    private static void addAll(Map<String, TextToSpeechCatalogItem> byId, List<TextToSpeechCatalogItem> items) {
        if (items == null) {
            return;
        }
        items.stream()
                .filter(Objects::nonNull)
                .forEach(item -> byId.putIfAbsent(item.id(), item));
    }
}
