package com.github.drafael.chat4j.tts.provider;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.persistence.catalog.CatalogGroup;
import com.github.drafael.chat4j.persistence.catalog.CatalogJsonStructure;
import com.github.drafael.chat4j.persistence.catalog.CatalogPayload;
import com.github.drafael.chat4j.persistence.catalog.CatalogSnapshotRead;
import com.github.drafael.chat4j.persistence.catalog.CatalogSnapshotStore;
import com.github.drafael.chat4j.persistence.catalog.SnapshotSlot;
import com.github.drafael.chat4j.persistence.catalog.SpeechCatalogKeySchema;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import lombok.NonNull;
import org.apache.commons.lang3.Validate;

import static java.util.Collections.emptyList;

public class TextToSpeechCatalogStore {

    private static final int MAX_CATALOG_ITEMS = 10_000;
    private static final int MAX_CATALOG_TOKENS = 500_000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(100)
                    .maxStringLength(64 * 1024)
                    .maxNumberLength(1_000)
                    .build())
            .build());
    private static final TypeReference<List<TextToSpeechCatalogItem>> ITEM_LIST_TYPE = new TypeReference<>() {
    };

    private final CatalogSnapshotStore snapshots;

    public TextToSpeechCatalogStore(SettingsRepository settingsRepo) {
        this(CatalogSnapshotStore.forSettings(settingsRepo));
    }

    public TextToSpeechCatalogStore(@NonNull CatalogSnapshotStore snapshots) {
        this.snapshots = snapshots;
    }

    public List<TextToSpeechCatalogItem> models(TextToSpeechProvider provider, TextToSpeechCatalogItem selected) {
        CatalogGroup group = SpeechCatalogKeySchema.tts(provider.id());
        CachedCatalogs cached = readCachedCatalogs(snapshots.read(group), group).orElseGet(CachedCatalogs::empty);
        return models(provider, selected, cached.models());
    }

    public List<TextToSpeechCatalogItem> voices(TextToSpeechProvider provider, TextToSpeechCatalogItem selected) {
        CatalogGroup group = SpeechCatalogKeySchema.tts(provider.id());
        CachedCatalogs cached = readCachedCatalogs(snapshots.read(group), group).orElseGet(CachedCatalogs::empty);
        return voices(provider, selected, cached.voices());
    }

    public Catalogs catalogs(
            TextToSpeechProvider provider,
            TextToSpeechCatalogItem selectedModel,
            TextToSpeechCatalogItem selectedVoice
    ) {
        CatalogGroup group = SpeechCatalogKeySchema.tts(provider.id());
        CachedCatalogs cached = readCachedCatalogs(snapshots.read(group), group).orElseGet(CachedCatalogs::empty);
        return new Catalogs(
                models(provider, selectedModel, cached.models()),
                voices(provider, selectedVoice, cached.voices())
        );
    }

    public void saveCatalogs(
            String providerId,
            @NonNull List<TextToSpeechCatalogItem> models,
            @NonNull List<TextToSpeechCatalogItem> voices
    ) {
        if (!saveCatalogsIf(providerId, models, voices, () -> true)) {
            throw new IllegalStateException("Failed to save Text to Speech catalogs");
        }
    }

    public boolean saveCatalogsIf(
            String providerId,
            @NonNull List<TextToSpeechCatalogItem> models,
            @NonNull List<TextToSpeechCatalogItem> voices,
            @NonNull BooleanSupplier condition
    ) {
        Validate.notBlank(providerId, "providerId should not be blank");
        try {
            String modelsJson = OBJECT_MAPPER.writeValueAsString(models);
            String voicesJson = OBJECT_MAPPER.writeValueAsString(voices);
            Validate.isTrue(parseItems(modelsJson).isPresent(), "Text to Speech models exceed structural limits");
            Validate.isTrue(parseItems(voicesJson).isPresent(), "Text to Speech voices exceed structural limits");
            return snapshots.saveIf(
                    SpeechCatalogKeySchema.tts(providerId),
                    List.of(CatalogPayload.of(modelsJson), CatalogPayload.of(voicesJson)),
                    condition
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save Text to Speech catalogs", e);
        }
    }

    public void invalidate(String providerId) {
        String slug = SpeechCatalogKeySchema.providerSlug(providerId);
        boolean invalidated = snapshots.invalidate(
                SpeechCatalogKeySchema.tts(providerId),
                List.of(
                        "chat4j.tts.%s.model.id".formatted(slug),
                        "chat4j.tts.%s.model.label".formatted(slug),
                        "chat4j.tts.%s.voice.id".formatted(slug),
                        "chat4j.tts.%s.voice.label".formatted(slug)
                )
        );
        if (!invalidated) {
            throw new IllegalStateException("Failed to invalidate Text to Speech catalogs");
        }
    }

    public List<TextToSpeechCatalogItem> mergeWithSelected(
            List<TextToSpeechCatalogItem> discovered,
            List<TextToSpeechCatalogItem> fallback,
            TextToSpeechCatalogItem selected
    ) {
        return merged(discovered, fallback, selected);
    }

    public List<TextToSpeechCatalogItem> authoritativeModels(
            @NonNull TextToSpeechProvider provider,
            @NonNull List<TextToSpeechCatalogItem> fetched
    ) {
        return authoritative(
                normalized(fetched, provider::normalizeModelSelection),
                normalized(provider.bundledModels(), provider::normalizeModelSelection)
        );
    }

    public List<TextToSpeechCatalogItem> authoritativeVoices(
            @NonNull TextToSpeechProvider provider,
            @NonNull List<TextToSpeechCatalogItem> fetched
    ) {
        return authoritative(
                normalized(fetched, provider::normalizeVoiceSelection),
                normalized(provider.bundledVoices(), provider::normalizeVoiceSelection)
        );
    }

    private List<TextToSpeechCatalogItem> models(
            TextToSpeechProvider provider,
            TextToSpeechCatalogItem selected,
            List<TextToSpeechCatalogItem> cached
    ) {
        return merged(
                normalized(cached, provider::normalizeModelSelection),
                provider.bundledModels(),
                normalized(selected, provider::normalizeModelSelection)
        );
    }

    private List<TextToSpeechCatalogItem> voices(
            TextToSpeechProvider provider,
            TextToSpeechCatalogItem selected,
            List<TextToSpeechCatalogItem> cached
    ) {
        return merged(
                normalized(cached, provider::normalizeVoiceSelection),
                provider.bundledVoices(),
                normalized(selected, provider::normalizeVoiceSelection)
        );
    }

    private Optional<CachedCatalogs> readCachedCatalogs(CatalogSnapshotRead snapshot, CatalogGroup group) {
        Optional<List<TextToSpeechCatalogItem>> models = readItems(snapshot, group.slots().getFirst());
        Optional<List<TextToSpeechCatalogItem>> voices = readItems(snapshot, group.slots().get(1));
        return models.flatMap(modelItems -> voices.map(voiceItems -> new CachedCatalogs(modelItems, voiceItems)));
    }

    private Optional<List<TextToSpeechCatalogItem>> readItems(CatalogSnapshotRead snapshot, SnapshotSlot slot) {
        return snapshot.payload(slot)
                .map(CatalogPayload::value)
                .filter(json -> !json.isBlank())
                .flatMap(this::parseItems);
    }

    private Optional<List<TextToSpeechCatalogItem>> parseItems(String json) {
        if (!CatalogJsonStructure.isBoundedArray(OBJECT_MAPPER, json, MAX_CATALOG_ITEMS, MAX_CATALOG_TOKENS)) {
            return Optional.empty();
        }
        try {
            List<TextToSpeechCatalogItem> items = OBJECT_MAPPER.readValue(json, ITEM_LIST_TYPE);
            return Optional.ofNullable(items);
        } catch (Exception e) {
            return Optional.empty();
        }
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

    private static List<TextToSpeechCatalogItem> authoritative(
            List<TextToSpeechCatalogItem> fetched,
            List<TextToSpeechCatalogItem> bundled
    ) {
        var byId = new LinkedHashMap<String, TextToSpeechCatalogItem>();
        addAll(byId, fetched);
        addAll(byId, bundled);
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

    private record CachedCatalogs(List<TextToSpeechCatalogItem> models, List<TextToSpeechCatalogItem> voices) {
        private static CachedCatalogs empty() {
            return new CachedCatalogs(emptyList(), emptyList());
        }
    }

    public record Catalogs(List<TextToSpeechCatalogItem> models, List<TextToSpeechCatalogItem> voices) {
        public Catalogs {
            models = List.copyOf(models);
            voices = List.copyOf(voices);
        }

        @Override
        public String toString() {
            return "Catalogs[modelCount=%d, voiceCount=%d]".formatted(models.size(), voices.size());
        }
    }
}
