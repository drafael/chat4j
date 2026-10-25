package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PerplexityModelIds {

    public static final List<String> AGENT_EFFORTS = List.of("fast", "low", "medium", "high");

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

    public static String agentEffort(String selection) {
        return switch (normalizeDirectModelId(selection)) {
            case "sonar", "sonar-pro", "fast" -> "fast";
            case "sonar-reasoning-pro", "low" -> "low";
            case "medium" -> "medium";
            case "sonar-deep-research", "high" -> "high";
            default -> throw new IllegalArgumentException("Unsupported Perplexity research selection: %s".formatted(selection));
        };
    }

    public static boolean isAgentOption(String selection) {
        return AGENT_EFFORTS.contains(normalizeDirectModelId(selection)) || isSonarModel(selection);
    }

    public static boolean isReasoningAgentOption(String selection) {
        return isReasoningSonarModel(selection)
                || Set.of("low", "medium", "high").contains(normalizeDirectModelId(selection));
    }

    public static boolean isSonarModel(String modelId) {
        return SONAR_MODEL_SET.contains(normalizeDirectModelId(modelId));
    }

    public static boolean isReasoningSonarModel(String modelId) {
        return REASONING_SONAR_MODELS.contains(normalizeDirectModelId(modelId));
    }

    public static boolean isNamespacedSonarModel(String modelId) {
        return StringUtils.trimToEmpty(modelId).toLowerCase(Locale.ROOT).startsWith("perplexity/")
            && isSonarModel(modelId);
    }

    public static boolean isNamespacedReasoningSonarModel(String modelId) {
        return StringUtils.trimToEmpty(modelId).toLowerCase(Locale.ROOT).startsWith("perplexity/")
            && isReasoningSonarModel(modelId);
    }

    private static String normalizeDirectModelId(String modelId) {
        String normalized = StringUtils.trimToEmpty(modelId).toLowerCase(Locale.ROOT);
        return normalized.startsWith("perplexity/")
                ? normalized.substring("perplexity/".length()).trim()
                : normalized;
    }
}
