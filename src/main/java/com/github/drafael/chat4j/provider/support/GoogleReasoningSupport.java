package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.ReasoningOptions;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.List;
import java.util.Optional;

/**
 * GenerateContent and OpenAI-compatibility thinking contracts, checked 2026-10-04.
 * @see <a href="https://ai.google.dev/gemini-api/docs/generate-content/thinking">Native thinking controls</a>
 * @see <a href="https://ai.google.dev/gemini-api/docs/openai">OpenAI compatibility mappings</a>
 */
public final class GoogleReasoningSupport {

    private GoogleReasoningSupport() {
    }

    public static Optional<ReasoningOptions> options(String modelId) {
        String model = model(modelId);
        return Optional.ofNullable(switch (model) {
            case "gemini-2.5-pro" -> ReasoningOptions.of(List.of(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH));
            case "gemini-2.5-flash", "gemini-2.5-flash-lite" -> ReasoningOptions.of(List.of(
                    ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH),
                    model.equals("gemini-2.5-flash-lite") ? ReasoningLevel.OFF : ReasoningLevel.MEDIUM);
            case "gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.1-pro-preview", "gemini-3.1-pro-preview-customtools" ->
                    ReasoningOptions.of(List.of(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH),
                            model.contains("pro") ? ReasoningLevel.HIGH : ReasoningLevel.MEDIUM);
            case "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3-flash-preview" -> ReasoningOptions.of(List.of(
                    ReasoningLevel.MINIMAL, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH),
                    model.equals("gemini-3-flash-preview") ? ReasoningLevel.HIGH : ReasoningLevel.MEDIUM);
            case "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.1-flash-lite-preview" -> ReasoningOptions.of(List.of(
                    ReasoningLevel.MINIMAL, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH), ReasoningLevel.MINIMAL);
            case "gemini-3.1-flash-image", "gemini-3.1-flash-image-preview", "gemini-3.1-flash-lite-image" ->
                    ReasoningOptions.of(List.of(ReasoningLevel.MINIMAL, ReasoningLevel.HIGH), ReasoningLevel.MINIMAL);
            case "gemini-3-pro-image", "gemini-3-pro-image-preview", "nano-banana-pro-preview" ->
                    ReasoningOptions.of(List.of(ReasoningLevel.MEDIUM));
            default -> null;
        });
    }

    public static Integer thinkingBudget(String modelId, @NonNull ReasoningLevel level) {
        if (!model(modelId).startsWith("gemini-2.5-") || options(modelId).isEmpty()) {
            return null;
        }
        return switch (level) {
            case OFF -> model(modelId).equals("gemini-2.5-pro") ? -1 : 0;
            case MINIMAL, LOW -> 1024;
            case MEDIUM -> 8192;
            case HIGH, EXTRA_HIGH, MAX, ULTRA -> 24576;
        };
    }

    public static String thinkingLevel(String modelId, @NonNull ReasoningLevel level) {
        return options(modelId).filter(options -> options.levels().size() > 1)
                .filter(ignored -> model(modelId).startsWith("gemini-3"))
                .map(options -> options.select(level).toSettingValue()).orElse(null);
    }

    private static String model(String modelId) {
        return Strings.CS.removeStart(StringUtils.trimToEmpty(modelId), "models/");
    }
}
