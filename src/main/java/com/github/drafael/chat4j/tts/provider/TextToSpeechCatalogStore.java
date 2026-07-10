package com.github.drafael.chat4j.tts.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        return merged(readItems(settings(provider.id()).catalogModelsKey()), provider.bundledModels(), selected);
    }

    public List<TextToSpeechCatalogItem> voices(TextToSpeechProvider provider, TextToSpeechCatalogItem selected) {
        return merged(readItems(settings(provider.id()).catalogVoicesKey()), provider.bundledVoices(), selected);
    }

    public void saveModels(String providerId, List<TextToSpeechCatalogItem> models) {
        TextToSpeechProviderSettings providerSettings = settings(providerId);
        saveItems(providerSettings.catalogModelsKey(), providerSettings.catalogUpdatedAtKey(), models);
    }

    public void saveVoices(String providerId, List<TextToSpeechCatalogItem> voices) {
        TextToSpeechProviderSettings providerSettings = settings(providerId);
        saveItems(providerSettings.catalogVoicesKey(), providerSettings.catalogUpdatedAtKey(), voices);
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

    private void saveItems(String itemsKey, String updatedAtKey, List<TextToSpeechCatalogItem> items) {
        try {
            String json = OBJECT_MAPPER.writeValueAsString(items == null ? emptyList() : items);
            settingsRepo.updateBatch(batch -> {
                batch.put(itemsKey, json);
                batch.put(updatedAtKey, Instant.now().toString());
            });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save Text to Speech catalog", e);
        }
    }

    private TextToSpeechProviderSettings settings(String providerId) {
        return TextToSpeechProviderSettingsFactory.forProvider(settingsRepo, providerId);
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
