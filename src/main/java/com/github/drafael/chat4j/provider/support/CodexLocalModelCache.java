package com.github.drafael.chat4j.provider.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.drafael.chat4j.json.JsonCodec;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

import static java.util.Collections.emptyList;

@Slf4j
public final class CodexLocalModelCache {

    private static final JsonCodec JSON = JsonCodec.standard();
    private static final String CODEX_PROVIDER_NAME = "OpenAI Codex";
    private static final List<String> BUILTIN_CODEX_MODELS = List.of(
            "gpt-5.5",
            "gpt-5.4",
            "gpt-5.4-mini",
            "gpt-5.3-codex",
            "gpt-5.3-codex-spark",
            "gpt-5.2-codex",
            "gpt-5.2",
            "gpt-5.1-codex-max",
            "gpt-5.1-codex-mini",
            "gpt-5.1",
            "gpt-5-codex"
    );

    private CodexLocalModelCache() {
    }

    public static Snapshot builtinSnapshot() {
        return new Snapshot(BUILTIN_CODEX_MODELS, emptyList());
    }

    public static Snapshot readSnapshot() {
        return readSnapshot(Path.of(System.getProperty("user.home")));
    }

    static Snapshot readSnapshot(Path userHome) {
        LocalModels localModels = readLocalCacheModels(userHome);
        LinkedHashSet<String> models = new LinkedHashSet<>(BUILTIN_CODEX_MODELS);
        models.addAll(localModels.visible());
        models.removeAll(localModels.hidden());
        return new Snapshot(
                ModelOrdering.sanitizeAndSortByProvider(CODEX_PROVIDER_NAME, models.stream().toList()),
                localModels.hidden(),
                localModels.loadedSuccessfully()
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
            return new LocalModels(visible, hidden, true);
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

    public static List<String> merge(List<String> modelIds, Snapshot localSnapshot) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (modelIds != null) {
            merged.addAll(modelIds);
        }
        merged.addAll(localSnapshot.models());
        merged.removeAll(localSnapshot.hiddenModels());
        return ModelOrdering.sanitizeAndSortByProvider(CODEX_PROVIDER_NAME, merged.stream().toList());
    }

    public record Snapshot(List<String> models, List<String> hiddenModels, boolean loadedSuccessfully) {
        public Snapshot(List<String> models, List<String> hiddenModels) {
            this(models, hiddenModels, true);
        }

        public Snapshot {
            models = List.copyOf(models);
            hiddenModels = List.copyOf(hiddenModels);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModelCache(List<CachedModel> models) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CachedModel(String slug, String visibility) {
    }

    private record LocalModels(List<String> visible, List<String> hidden, boolean loadedSuccessfully) {
        private static LocalModels empty(boolean loadedSuccessfully) {
            return new LocalModels(emptyList(), emptyList(), loadedSuccessfully);
        }
    }
}
