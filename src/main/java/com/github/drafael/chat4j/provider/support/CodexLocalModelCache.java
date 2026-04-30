package com.github.drafael.chat4j.provider.support;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static java.util.Collections.emptyList;

@Slf4j
public final class CodexLocalModelCache {

    private static final Pattern CODEX_SLUG_PATTERN = Pattern.compile("\\\"slug\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
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

    public static List<String> readModels() {
        LinkedHashSet<String> models = new LinkedHashSet<>(BUILTIN_CODEX_MODELS);
        models.addAll(readLocalCacheModels());
        return ModelOrdering.sanitizeAndSortByProvider(CODEX_PROVIDER_NAME, models.stream().toList());
    }

    private static List<String> readLocalCacheModels() {
        try {
            Path modelCache = Path.of(System.getProperty("user.home"), ".codex", "models_cache.json");
            if (!Files.exists(modelCache)) {
                return emptyList();
            }

            String content = Files.readString(modelCache, StandardCharsets.UTF_8);
            Matcher matcher = CODEX_SLUG_PATTERN.matcher(content);
            LinkedHashSet<String> slugs = new LinkedHashSet<>();
            while (matcher.find()) {
                slugs.add(matcher.group(1));
            }

            return slugs.stream().toList();
        } catch (Exception e) {
            log.warn("Failed reading OpenAI Codex models cache: {}", ExceptionUtils.getMessage(e));
            return emptyList();
        }
    }

    public static List<String> mergeIfCodexProvider(String providerName, List<String> modelIds) {
        if (!StringUtils.equals(providerName, CODEX_PROVIDER_NAME)) {
            return ModelOrdering.sanitizeAndSortByProvider(providerName, modelIds);
        }

        SequencedSet<String> merged = new LinkedHashSet<>(readModels());
        if (modelIds != null) {
            merged.addAll(modelIds);
        }

        return ModelOrdering.sanitizeAndSortByProvider(CODEX_PROVIDER_NAME, merged.stream().toList());
    }
}
