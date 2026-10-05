package com.github.drafael.chat4j.provider.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.drafael.chat4j.json.JsonCodec;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.ReasoningOptions;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toMap;

@Slf4j
public final class CodexLocalModelCache {

    private static final JsonCodec JSON = JsonCodec.standard();
    private static final String CODEX_PROVIDER_NAME = "OpenAI Codex";
    // Visible models and efforts from the official Codex catalog, checked 2026-10-04:
    // https://github.com/openai/codex/blob/main/codex-rs/models-manager/models.json
    private static final List<String> BUILTIN_CODEX_MODELS = List.of(
            "gpt-6-astra",
            "gpt-6.1-sol",
            "gpt-6-sol",
            "gpt-6-luna",
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
            "gpt-5.5"
    );
    private static final List<ReasoningLevel> GPT_5_5_REASONING_LEVELS = List.of(
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.EXTRA_HIGH
    );
    private static final List<ReasoningLevel> MAX_REASONING_LEVELS = List.of(
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.EXTRA_HIGH,
            ReasoningLevel.MAX
    );
    private static final List<ReasoningLevel> ULTRA_REASONING_LEVELS = List.of(
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.EXTRA_HIGH,
            ReasoningLevel.MAX,
            ReasoningLevel.ULTRA
    );
    private static final Map<String, List<ReasoningLevel>> BUILTIN_REASONING_LEVELS = Map.of(
            "gpt-6-astra", ULTRA_REASONING_LEVELS,
            "gpt-6.1-sol", ULTRA_REASONING_LEVELS,
            "gpt-6-sol", ULTRA_REASONING_LEVELS,
            "gpt-6-luna", MAX_REASONING_LEVELS,
            "gpt-5.6-sol", ULTRA_REASONING_LEVELS,
            "gpt-5.6-terra", ULTRA_REASONING_LEVELS,
            "gpt-5.6-luna", MAX_REASONING_LEVELS,
            "gpt-5.5", GPT_5_5_REASONING_LEVELS
    );

    private static final Map<String, ReasoningLevel> BUILTIN_DEFAULT_LEVELS = Map.of(
            "gpt-6-astra", ReasoningLevel.LOW,
            "gpt-6.1-sol", ReasoningLevel.LOW,
            "gpt-6-sol", ReasoningLevel.MEDIUM,
            "gpt-6-luna", ReasoningLevel.MEDIUM,
            "gpt-5.6-sol", ReasoningLevel.LOW,
            "gpt-5.6-terra", ReasoningLevel.MEDIUM,
            "gpt-5.6-luna", ReasoningLevel.MEDIUM,
            "gpt-5.5", ReasoningLevel.MEDIUM
    );

    private CodexLocalModelCache() {
    }

    public static Snapshot builtinSnapshot() {
        return new Snapshot(BUILTIN_CODEX_MODELS, emptyList(), BUILTIN_REASONING_LEVELS, BUILTIN_DEFAULT_LEVELS, true, false);
    }

    public static Snapshot readSnapshot() {
        return readSnapshot(Path.of(System.getProperty("user.home")));
    }

    static Snapshot readSnapshot(Path userHome) {
        LocalModels localModels = readLocalCacheModels(userHome);
        LinkedHashSet<String> models = new LinkedHashSet<>(
                localModels.authoritative() ? localModels.visible() : BUILTIN_CODEX_MODELS
        );
        models.removeAll(localModels.hidden());
        Map<String, List<ReasoningLevel>> reasoningLevels = new LinkedHashMap<>(BUILTIN_REASONING_LEVELS);
        reasoningLevels.putAll(localModels.reasoningLevelsByModel());
        reasoningLevels.keySet().retainAll(models);
        return new Snapshot(
                ModelOrdering.sanitizeAndSortByProvider(CODEX_PROVIDER_NAME, models.stream().toList()),
                localModels.hidden(),
                reasoningLevels,
                localModels.authoritative() ? localModels.defaultLevelsByModel() : BUILTIN_DEFAULT_LEVELS,
                localModels.loadedSuccessfully(),
                localModels.authoritative()
        );
    }

    private static LocalModels readLocalCacheModels(Path userHome) {
        try {
            Path modelCache = userHome.resolve(".codex").resolve("models_cache.json");
            if (!Files.exists(modelCache)) {
                return LocalModels.empty(true);
            }

            ModelCache cache = JSON.read(Files.readString(modelCache, StandardCharsets.UTF_8), ModelCache.class);
            if (cache.models() == null) {
                return LocalModels.empty(false);
            }

            List<String> visible = modelSlugs(cache.models(), false);
            List<String> hidden = modelSlugs(cache.models(), true);
            Map<String, ReasoningLevel> defaults = new LinkedHashMap<>();
            cache.models().stream()
                    .filter(Objects::nonNull)
                    .filter(model -> visible.contains(StringUtils.trim(model.slug())))
                    .forEach(model -> {
                        ReasoningLevel level = ReasoningLevel.fromSettingValue(model.defaultReasoningLevel(), null);
                        if (level != null) {
                            defaults.put(model.slug().trim(), level);
                        }
                    });
            return new LocalModels(visible, hidden, reasoningLevelsByModel(cache.models()), defaults, true, true);
        } catch (Exception e) {
            log.warn("Failed reading OpenAI Codex models cache: {}", ExceptionUtils.getMessage(e));
            return LocalModels.empty(false);
        }
    }

    private static List<String> modelSlugs(List<CachedModel> models, boolean hidden) {
        return models.stream()
                .filter(model -> model != null && Strings.CI.equals(model.visibility(), "hide") == hidden)
                .map(CachedModel::slug)
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static Map<String, List<ReasoningLevel>> reasoningLevelsByModel(List<CachedModel> models) {
        return models.stream()
                .filter(model -> model != null && !Strings.CI.equals(model.visibility(), "hide"))
                .filter(model -> StringUtils.isNotBlank(model.slug()))
                .filter(model -> model.supportedReasoningLevels() != null)
                .collect(toMap(
                        model -> model.slug().trim(),
                        model -> {
                            List<ReasoningLevel> levels = model.supportedReasoningLevels().stream()
                                    .filter(Objects::nonNull)
                                    .map(SupportedReasoningLevel::effort)
                                    .map(effort -> ReasoningLevel.fromSettingValue(effort, null))
                                    .filter(Objects::nonNull)
                                    .distinct()
                                    .sorted()
                                    .toList();
                            Validate.isTrue(model.supportedReasoningLevels().isEmpty() || !levels.isEmpty(),
                                    "Codex effort metadata contains no recognized levels for model %s", model.slug());
                            return levels;
                        },
                        (first, second) -> second,
                        LinkedHashMap::new
                ));
    }

    public static List<String> merge(List<String> modelIds, Snapshot localSnapshot) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (!localSnapshot.authoritative() && modelIds != null) {
            merged.addAll(modelIds);
        }
        merged.addAll(localSnapshot.models());
        merged.removeAll(localSnapshot.hiddenModels());
        return ModelOrdering.sanitizeAndSortByProvider(CODEX_PROVIDER_NAME, merged.stream().toList());
    }

    public record Snapshot(
            List<String> models,
            List<String> hiddenModels,
            Map<String, List<ReasoningLevel>> reasoningLevelsByModel,
            Map<String, ReasoningLevel> defaultLevelsByModel,
            boolean loadedSuccessfully,
            boolean authoritative
    ) {
        public Snapshot(List<String> models, List<String> hiddenModels) {
            this(models, hiddenModels, emptyMap(), emptyMap(), true, false);
        }

        public Snapshot(List<String> models, List<String> hiddenModels, boolean loadedSuccessfully) {
            this(models, hiddenModels, emptyMap(), emptyMap(), loadedSuccessfully, false);
        }

        public Snapshot(List<String> models, List<String> hiddenModels, Map<String, List<ReasoningLevel>> reasoningLevelsByModel) {
            this(models, hiddenModels, reasoningLevelsByModel, emptyMap(), true, false);
        }

        public Snapshot {
            models = List.copyOf(models);
            hiddenModels = List.copyOf(hiddenModels);
            reasoningLevelsByModel = reasoningLevelsByModel.entrySet().stream()
                    .collect(toMap(
                            Map.Entry::getKey,
                            entry -> List.copyOf(entry.getValue()),
                            (first, second) -> second,
                            LinkedHashMap::new
                    ));
            reasoningLevelsByModel = Map.copyOf(reasoningLevelsByModel);
            defaultLevelsByModel = Map.copyOf(defaultLevelsByModel);
        }

        public Optional<ReasoningOptions> reasoningOptions(String modelId) {
            return Optional.ofNullable(reasoningLevelsByModel.get(modelId)).map(levels -> levels.isEmpty()
                    ? ReasoningOptions.UNAVAILABLE
                    : ReasoningOptions.of(levels, defaultLevelsByModel.getOrDefault(modelId, ReasoningLevel.MEDIUM)));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModelCache(List<CachedModel> models) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CachedModel(
            String slug,
            String visibility,
            @JsonProperty("supported_reasoning_levels") List<SupportedReasoningLevel> supportedReasoningLevels,
            @JsonProperty("default_reasoning_level") String defaultReasoningLevel
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SupportedReasoningLevel(String effort) {
    }

    private record LocalModels(
            List<String> visible,
            List<String> hidden,
            Map<String, List<ReasoningLevel>> reasoningLevelsByModel,
            Map<String, ReasoningLevel> defaultLevelsByModel,
            boolean loadedSuccessfully,
            boolean authoritative
    ) {
        private static LocalModels empty(boolean loadedSuccessfully) {
            return new LocalModels(emptyList(), emptyList(), emptyMap(), emptyMap(), loadedSuccessfully, false);
        }
    }
}
