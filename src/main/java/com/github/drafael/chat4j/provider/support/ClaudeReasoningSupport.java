package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Set;

/** Claude thinking controls reviewed against Anthropic documentation and OpenRouter's catalog on 2026-10-03. */
public final class ClaudeReasoningSupport {

    private static final Set<String> ADAPTIVE_MODELS = Set.of(
            "claude-sonnet-4-6", "claude-opus-4-6", "claude-opus-4-7", "claude-opus-4-8",
            "claude-sonnet-5", "claude-sonnet-5-5", "claude-opus-5", "claude-opus-5-5",
            "claude-fable-5", "claude-fable-5-1"
    );

    private ClaudeReasoningSupport() {
    }

    public static boolean usesAdaptiveThinking(String modelId) {
        return ADAPTIVE_MODELS.contains(normalizeModelId(modelId));
    }

    public static boolean requiresThinking(String modelId, boolean openRouter) {
        return switch (normalizeModelId(modelId)) {
            case "claude-opus-5-5", "claude-fable-5", "claude-fable-5-1" -> true;
            case "claude-sonnet-5-5" -> openRouter;
            default -> false;
        };
    }

    public static List<ReasoningLevel> availableLevels(String modelId, boolean openRouter) {
        if (!usesAdaptiveThinking(modelId)) {
            return ReasoningLevel.standardLevels();
        }
        boolean supportsExtraHigh = !normalizeModelId(modelId).endsWith("-4-6");
        return List.of(ReasoningLevel.values()).stream()
                .filter(level -> level != ReasoningLevel.ULTRA)
                .filter(level -> level != ReasoningLevel.OFF || !requiresThinking(modelId, openRouter))
                .filter(level -> level != ReasoningLevel.EXTRA_HIGH || supportsExtraHigh)
                .toList();
    }

    private static String normalizeModelId(String modelId) {
        String model = StringUtils.substringBefore(StringUtils.trimToEmpty(modelId), ":");
        return model.startsWith("anthropic/")
                ? model.substring("anthropic/".length()).replace('.', '-')
                : model;
    }
}
