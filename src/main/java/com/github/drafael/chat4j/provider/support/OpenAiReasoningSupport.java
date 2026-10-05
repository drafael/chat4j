package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import lombok.NonNull;
import org.apache.commons.lang3.Strings;

import java.util.Map;

import static java.util.Collections.emptyMap;

/** Shared Chat Completions controls for ordinary chat and tool turns. Local capability checks may perform I/O. */
public final class OpenAiReasoningSupport {

    private OpenAiReasoningSupport() {
    }

    public static Map<String, Object> properties(String provider, String model, String baseUrl, String apiKey, @NonNull ReasoningLevel level) {
        if (Strings.CS.equals(provider, "OpenRouter")) {
            return Map.of("reasoning", openRouterReasoning(model, level));
        }
        if (Strings.CS.equalsAny(provider, "Ollama", "LM Studio")
                && ProviderCapabilityResolver.supportsReasoning(provider, model, baseUrl, apiKey)) {
            String localEffort = switch (level) {
                case OFF -> "none";
                case EXTRA_HIGH -> "xhigh";
                default -> level.toSettingValue();
            };
            return Map.of("reasoning_effort", localEffort);
        }
        if (Strings.CS.equals(provider, "DeepSeek")) {
            if (!level.enabled()) {
                return Map.of("thinking", Map.of("type", "disabled"));
            }
            String deepSeekEffort = switch (level) {
                case OFF, MINIMAL, LOW -> "low";
                case MEDIUM, HIGH -> "high";
                case EXTRA_HIGH, MAX, ULTRA -> "max";
            };
            return Map.of("thinking", Map.of("type", "enabled"), "reasoning_effort", deepSeekEffort);
        }
        if (!level.enabled()) {
            return ProviderCapabilityResolver.supportsExplicitReasoningOff(provider, model)
                    ? Map.of("reasoning_effort", "none") : emptyMap();
        }
        return Map.of("reasoning_effort", effort(level));
    }

    public static String effort(@NonNull ReasoningLevel level) {
        return switch (level) {
            case OFF -> "none";
            case EXTRA_HIGH -> "xhigh";
            case ULTRA -> "max";
            default -> level.toSettingValue();
        };
    }

    private static Map<String, Object> openRouterReasoning(String model, ReasoningLevel level) {
        if (Strings.CS.startsWith(model, "anthropic/claude-")) {
            if (level.enabled()) {
                return Map.of("effort", ClaudeReasoningSupport.effort(model, level), "exclude", false);
            }
            return ClaudeReasoningSupport.requiresThinking(model, true)
                    ? Map.of("effort", "low", "exclude", true) : Map.of("enabled", false);
        }
        ReasoningLevel effective = level.enabled() ? level : OpenRouterReasoningSupport.options(model)
                .filter(options -> !options.levels().contains(ReasoningLevel.OFF))
                .map(options -> options.levels().getFirst()).orElse(ReasoningLevel.OFF);
        return effective.enabled()
                ? Map.of("effort", effort(effective), "exclude", !level.enabled()) : Map.of("enabled", false);
    }
}
