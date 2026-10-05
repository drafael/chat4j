package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.emptyMap;

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
        AnthropicOffMode offMode = anthropicOffMode(modelId);
        return offMode == AnthropicOffMode.OMITTED_DISPLAY
                || (openRouter && offMode == AnthropicOffMode.BETWEEN_TOOLS);
    }

    private static AnthropicOffMode anthropicOffMode(String modelId) {
        return switch (normalizeModelId(modelId)) {
            case "claude-sonnet-5-5" -> AnthropicOffMode.BETWEEN_TOOLS;
            case "claude-sonnet-5", "claude-opus-5" -> AnthropicOffMode.DISABLED;
            case "claude-opus-5-5", "claude-fable-5", "claude-fable-5-1" -> AnthropicOffMode.OMITTED_DISPLAY;
            default -> AnthropicOffMode.OMIT_CONFIGURATION;
        };
    }

    public static String effort(String modelId, @NonNull ReasoningLevel level) {
        return switch (level) {
            case OFF, MINIMAL, LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH -> "high";
            case EXTRA_HIGH -> normalizeModelId(modelId).endsWith("-4-6") ? "max" : "xhigh";
            case MAX, ULTRA -> "max";
        };
    }

    public static int budgetTokens(@NonNull ReasoningLevel level) {
        return switch (level) {
            case OFF -> 0;
            case MINIMAL, LOW -> 1024;
            case MEDIUM -> 2048;
            case HIGH -> 4096;
            case EXTRA_HIGH, MAX, ULTRA -> 8192;
        };
    }

    public static Map<String, Object> requestProperties(String modelId, @NonNull ReasoningLevel level) {
        if (!usesAdaptiveThinking(modelId)) {
            return level.enabled() ? Map.of("thinking", Map.of("type", "enabled", "budget_tokens", budgetTokens(level))) : emptyMap();
        }
        if (level.enabled()) {
            return Map.of("thinking", Map.of("type", "adaptive", "display", "summarized"),
                    "output_config", Map.of("effort", effort(modelId, level)));
        }
        Map<String, Object> thinking = switch (anthropicOffMode(modelId)) {
            case BETWEEN_TOOLS -> Map.of("type", "between_tools");
            case DISABLED -> Map.of("type", "disabled");
            case OMITTED_DISPLAY -> Map.of("type", "adaptive", "display", "omitted");
            case OMIT_CONFIGURATION -> emptyMap();
        };
        return thinking.isEmpty() ? emptyMap() : Map.of("thinking", thinking, "output_config", Map.of("effort", "low"));
    }

    private enum AnthropicOffMode {
        OMIT_CONFIGURATION,
        DISABLED,
        BETWEEN_TOOLS,
        OMITTED_DISPLAY
    }

    public static List<ReasoningLevel> availableLevels(String modelId, boolean openRouter) {
        if (!usesAdaptiveThinking(modelId)) {
            return ReasoningLevel.standardLevels();
        }
        boolean supportsExtraHigh = !normalizeModelId(modelId).endsWith("-4-6");
        return List.of(ReasoningLevel.values()).stream()
                .filter(level -> level != ReasoningLevel.ULTRA && level != ReasoningLevel.MINIMAL)
                .filter(level -> level != ReasoningLevel.OFF || !requiresThinking(modelId, openRouter))
                .filter(level -> level != ReasoningLevel.EXTRA_HIGH || supportsExtraHigh)
                .toList();
    }

    /** General-purpose recommendations from the Claude effort guide, checked 2026-10-04. */
    public static ReasoningLevel recommendedLevel(String modelId) {
        if (!usesAdaptiveThinking(modelId)) {
            return ReasoningLevel.MEDIUM;
        }
        return switch (normalizeModelId(modelId)) {
            case "claude-sonnet-4-6", "claude-opus-5-5" -> ReasoningLevel.MEDIUM;
            default -> ReasoningLevel.HIGH;
        };
    }

    private static String normalizeModelId(String modelId) {
        String model = StringUtils.substringBefore(StringUtils.trimToEmpty(modelId), ":");
        return model.startsWith("anthropic/")
                ? model.substring("anthropic/".length()).replace('.', '-')
                : model;
    }
}
