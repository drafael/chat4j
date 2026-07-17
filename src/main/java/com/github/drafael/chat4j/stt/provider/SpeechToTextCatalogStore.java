package com.github.drafael.chat4j.stt.provider;

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
import com.github.drafael.chat4j.stt.provider.assemblyai.AssemblyAiSpeechToTextProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

import static java.util.Collections.emptyList;

public class SpeechToTextCatalogStore {

    private static final int MAX_CATALOG_ITEMS = 10_000;
    private static final int MAX_CATALOG_TOKENS = 500_000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(100)
                    .maxStringLength(64 * 1024)
                    .maxNumberLength(1_000)
                    .build())
            .build());
    private static final Duration STALE_AFTER = Duration.ofHours(24);

    private final CatalogSnapshotStore snapshots;
    private final Clock clock;

    public SpeechToTextCatalogStore(@NonNull SettingsRepository settingsRepo) {
        this(CatalogSnapshotStore.forSettings(settingsRepo));
    }

    public SpeechToTextCatalogStore(@NonNull CatalogSnapshotStore snapshots) {
        this(snapshots, Clock.systemUTC());
    }

    public SpeechToTextCatalogStore(@NonNull CatalogSnapshotStore snapshots, @NonNull Clock clock) {
        this.snapshots = snapshots;
        this.clock = clock;
    }

    public List<SpeechToTextCatalogItem> models(
            @NonNull SpeechToTextProvider provider,
            SpeechToTextCatalogItem selected
    ) {
        return catalog(provider, selected).models();
    }

    public CatalogState catalog(
            @NonNull SpeechToTextProvider provider,
            SpeechToTextCatalogItem selected
    ) {
        CatalogGroup group = SpeechCatalogKeySchema.sttModels(provider.id());
        CatalogSnapshotRead snapshot = snapshots.read(group);
        Optional<List<SpeechToTextCatalogItem>> cached = readCachedModels(snapshot, group.slots().getFirst());
        List<SpeechToTextCatalogItem> models = AssemblyAiSpeechToTextProvider.ID.equals(provider.id())
                ? provider.bundledModels().stream()
                        .filter(item -> AssemblyAiSpeechToTextProvider.isBundledModelId(item.id()))
                        .toList()
                : mergeWithSelected(
                        normalized(cached.orElse(emptyList()), provider::normalizeModelSelection),
                        provider.bundledModels(),
                        normalized(selected, provider::normalizeModelSelection)
                );
        boolean stale = cached.isEmpty() || snapshot.updatedAt().map(this::isStaleTimestamp).orElse(true);
        return new CatalogState(models, stale);
    }

    public List<SpeechToTextCatalogItem> cachedModels(String providerId) {
        CatalogGroup group = SpeechCatalogKeySchema.sttModels(providerId);
        return readCachedModels(snapshots.read(group), group.slots().getFirst()).orElse(emptyList());
    }

    public boolean stale(String providerId) {
        CatalogGroup group = SpeechCatalogKeySchema.sttModels(providerId);
        CatalogSnapshotRead snapshot = snapshots.read(group);
        return readCachedModels(snapshot, group.slots().getFirst()).isEmpty()
                || snapshot.updatedAt().map(this::isStaleTimestamp).orElse(true);
    }

    public void invalidate(String providerId) {
        String slug = SpeechCatalogKeySchema.providerSlug(providerId);
        boolean invalidated = snapshots.invalidate(
                SpeechCatalogKeySchema.sttModels(providerId),
                List.of("chat4j.stt.%s.model.id".formatted(slug), "chat4j.stt.%s.model.label".formatted(slug))
        );
        if (!invalidated) {
            throw new IllegalStateException("Failed to invalidate Speech to Text catalog");
        }
    }

    public void saveModels(String providerId, @NonNull List<SpeechToTextCatalogItem> models) throws Exception {
        if (!saveModelsIf(providerId, models, () -> true)) {
            throw new IllegalStateException("Failed to save Speech to Text catalog");
        }
    }

    public boolean saveModelsIf(
            String providerId,
            @NonNull List<SpeechToTextCatalogItem> models,
            @NonNull BooleanSupplier condition
    ) throws Exception {
        Validate.notBlank(providerId, "providerId should not be blank");
        String modelsJson = OBJECT_MAPPER.writeValueAsString(models);
        Validate.isTrue(deserialize(modelsJson).isPresent(), "Speech to Text catalog exceeds structural limits");
        return snapshots.saveIf(
                SpeechCatalogKeySchema.sttModels(providerId),
                List.of(CatalogPayload.of(modelsJson)),
                condition
        );
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

    public List<SpeechToTextCatalogItem> authoritativeModels(
            @NonNull SpeechToTextProvider provider,
            @NonNull List<SpeechToTextCatalogItem> fetched
    ) {
        var authoritative = new LinkedHashMap<String, SpeechToTextCatalogItem>();
        addAll(authoritative, normalized(fetched, provider::normalizeModelSelection));
        addAll(authoritative, normalized(provider.bundledModels(), provider::normalizeModelSelection));
        return authoritative.values().stream().toList();
    }

    private Optional<List<SpeechToTextCatalogItem>> readCachedModels(
            CatalogSnapshotRead snapshot,
            SnapshotSlot slot
    ) {
        return snapshot.payload(slot).map(CatalogPayload::value).flatMap(this::deserialize);
    }

    private Optional<List<SpeechToTextCatalogItem>> deserialize(String json) {
        if (!CatalogJsonStructure.isBoundedArray(OBJECT_MAPPER, json, MAX_CATALOG_ITEMS, MAX_CATALOG_TOKENS)) {
            return Optional.empty();
        }
        try {
            List<SpeechToTextCatalogItem> items = OBJECT_MAPPER.readValue(json, new TypeReference<>() {
            });
            return Optional.ofNullable(items);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private boolean isStaleTimestamp(String value) {
        try {
            return Instant.parse(value).plus(STALE_AFTER).isBefore(clock.instant());
        } catch (Exception e) {
            return true;
        }
    }

    private static SpeechToTextCatalogItem normalized(
            SpeechToTextCatalogItem item,
            Function<SpeechToTextCatalogItem, SpeechToTextCatalogItem> normalizer
    ) {
        return item == null ? null : normalizer.apply(item);
    }

    private static List<SpeechToTextCatalogItem> normalized(
            List<SpeechToTextCatalogItem> items,
            Function<SpeechToTextCatalogItem, SpeechToTextCatalogItem> normalizer
    ) {
        if (items == null) {
            return emptyList();
        }
        return items.stream()
                .filter(item -> item != null && StringUtils.isNotBlank(item.id()))
                .map(normalizer)
                .filter(item -> item != null && StringUtils.isNotBlank(item.id()))
                .toList();
    }

    private static void addAll(Map<String, SpeechToTextCatalogItem> target, List<SpeechToTextCatalogItem> items) {
        if (items == null) {
            return;
        }
        items.stream()
                .filter(item -> item != null && StringUtils.isNotBlank(item.id()))
                .forEach(item -> target.putIfAbsent(item.id(), item));
    }

    public record CatalogState(@NonNull List<SpeechToTextCatalogItem> models, boolean stale) {
        public CatalogState {
            models = List.copyOf(models);
        }
    }
}
