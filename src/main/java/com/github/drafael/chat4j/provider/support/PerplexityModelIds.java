package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Set;

public final class PerplexityModelIds {

    public static final List<String> SONAR_MODELS = List.of(
            "sonar",
            "sonar-pro",
            "sonar-reasoning-pro",
            "sonar-deep-research"
    );

    private static final Set<String> SONAR_MODEL_SET = Set.copyOf(SONAR_MODELS);
    private static final Set<String> REASONING_SONAR_MODELS = Set.of("sonar-reasoning-pro", "sonar-deep-research");

    private PerplexityModelIds() {
    }

    public static boolean isSonarModel(String modelId) {
        return SONAR_MODEL_SET.contains(normalizeDirectModelId(modelId));
    }

    public static boolean isReasoningSonarModel(String modelId) {
        return REASONING_SONAR_MODELS.contains(normalizeDirectModelId(modelId));
    }

    public static boolean isNamespacedSonarModel(String modelId) {
        return StringUtils.trimToEmpty(modelId).toLowerCase().startsWith("perplexity/")
            && isSonarModel(modelId);
    }

    public static boolean isNamespacedReasoningSonarModel(String modelId) {
        return StringUtils.trimToEmpty(modelId).toLowerCase().startsWith("perplexity/")
            && isReasoningSonarModel(modelId);
    }

    private static String normalizeDirectModelId(String modelId) {
        String normalized = StringUtils.trimToEmpty(modelId).toLowerCase();
        return normalized.startsWith("perplexity/")
                ? normalized.substring("perplexity/".length()).trim()
                : normalized;
    }
}
