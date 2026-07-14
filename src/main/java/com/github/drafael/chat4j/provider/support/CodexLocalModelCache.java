package com.github.drafael.chat4j.provider.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.StreamSupport;

import static java.util.Collections.emptyList;

@Slf4j
public final class CodexLocalModelCache {

    private static final ObjectMapper JSON = new ObjectMapper();
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

            JsonNode models = JSON.readTree(Files.readString(modelCache, StandardCharsets.UTF_8)).path("models");
            if (!models.isArray()) {
                return LocalModels.empty(false);
            }

            List<String> visible = modelSlugs(models, false);
            List<String> hidden = modelSlugs(models, true);
            return new LocalModels(visible, hidden, true);
        } catch (Exception e) {
            log.warn("Failed reading OpenAI Codex models cache: {}", ExceptionUtils.getMessage(e));
            return LocalModels.empty(false);
        }
    }

    private static List<String> modelSlugs(JsonNode models, boolean hidden) {
        return StreamSupport.stream(models.spliterator(), false)
                .filter(model -> Strings.CI.equals(model.path("visibility").asText(""), "hide") == hidden)
                .map(model -> model.path("slug").asText(""))
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

    private record LocalModels(List<String> visible, List<String> hidden, boolean loadedSuccessfully) {
        private static LocalModels empty(boolean loadedSuccessfully) {
            return new LocalModels(emptyList(), emptyList(), loadedSuccessfully);
        }
    }
}
