package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.ReasoningOptions;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Route-specific contracts from OpenRouter's public model catalog, checked 2026-10-04.
 * @see <a href="https://openrouter.ai/api/v1/models">Public catalog including supported efforts and defaults</a>
 * @see <a href="https://openrouter.ai/docs/guides/best-practices/reasoning-tokens">Unified reasoning controls</a>
 */
public final class OpenRouterReasoningSupport {

    private OpenRouterReasoningSupport() {
    }

    public static Optional<ReasoningOptions> options(String modelId) {
        String model = StringUtils.substringBefore(StringUtils.trimToEmpty(modelId), ":");
        return Optional.ofNullable(switch (model) {
            case "openai/gpt-5", "openai/gpt-5-mini", "openai/gpt-5-nano" -> ReasoningOptions.of(List.of(
                    ReasoningLevel.MINIMAL, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH));
            case "openai/gpt-5.1" -> ReasoningOptions.of(List.of(
                    ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH), ReasoningLevel.OFF);
            case "openai/gpt-5.2", "openai/gpt-5.4", "openai/gpt-5.4-mini", "openai/gpt-5.4-nano", "openai/gpt-5.5" ->
                    ReasoningOptions.of(ReasoningLevel.standardLevels());
            case "openai/gpt-5.2-pro", "openai/gpt-5.4-pro", "openai/gpt-5.5-pro" -> ReasoningOptions.of(List.of(
                    ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.EXTRA_HIGH));
            case "openai/gpt-5-pro" -> ReasoningOptions.of(List.of(ReasoningLevel.HIGH));
            case "openai/gpt-6-astra", "openai/gpt-6-astra-pro", "openai/gpt-6.1-sol", "openai/gpt-6.1-sol-pro" ->
                    ReasoningOptions.of(List.of(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH,
                            ReasoningLevel.EXTRA_HIGH, ReasoningLevel.MAX));
            case "openai/gpt-6-sol", "openai/gpt-6-sol-pro", "openai/gpt-6-luna", "openai/gpt-6-luna-pro",
                 "openai/gpt-5.6-sol", "openai/gpt-5.6-sol-pro", "openai/gpt-5.6-terra", "openai/gpt-5.6-terra-pro",
                 "openai/gpt-5.6-luna", "openai/gpt-5.6-luna-pro" -> ReasoningOptions.of(List.of(
                    ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH,
                    ReasoningLevel.EXTRA_HIGH, ReasoningLevel.MAX));
            case "deepseek/deepseek-v4-pro", "deepseek/deepseek-v4-flash" -> ReasoningOptions.of(List.of(
                    ReasoningLevel.OFF, ReasoningLevel.HIGH, ReasoningLevel.EXTRA_HIGH), ReasoningLevel.HIGH);
            case "deepseek/deepseek-v4.1-flash", "deepseek/deepseek-v4-pro-0813", "deepseek/deepseek-v4-flash-0731",
                 "deepseek/deepseek-v4-flash-vision-exp" -> ReasoningOptions.of(List.of(
                    ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.HIGH, ReasoningLevel.MAX), ReasoningLevel.HIGH);
            case "mistralai/mistral-small-2603", "mistralai/mistral-medium-3-5" -> ReasoningOptions.of(List.of(
                    ReasoningLevel.OFF, ReasoningLevel.HIGH), ReasoningLevel.HIGH);
            case "x-ai/grok-4.3" -> ReasoningOptions.of(List.of(
                    ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH), ReasoningLevel.LOW);
            case "x-ai/grok-4.6", "x-ai/grok-4.7" -> ReasoningOptions.of(List.of(
                    ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.EXTRA_HIGH), ReasoningLevel.HIGH);
            case "google/gemini-3.8-flash", "google/gemini-3.7-flash", "google/gemini-3.1-pro-preview",
                 "google/gemini-3.1-pro-preview-customtools" -> ReasoningOptions.of(List.of(
                    ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH));
            case "google/gemini-3.6-flash", "google/gemini-3.5-flash", "google/gemini-3-flash-preview" ->
                    ReasoningOptions.of(List.of(ReasoningLevel.MINIMAL, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH));
            case "google/gemini-3.5-flash-lite", "google/gemini-3.1-flash-lite", "google/gemini-3.1-flash-lite-preview" ->
                    ReasoningOptions.of(List.of(ReasoningLevel.MINIMAL, ReasoningLevel.LOW, ReasoningLevel.MEDIUM,
                            ReasoningLevel.HIGH), ReasoningLevel.MINIMAL);
            case "google/gemini-3.1-flash-image", "google/gemini-3.1-flash-image-preview", "google/gemini-3.1-flash-lite-image" ->
                    ReasoningOptions.of(List.of(ReasoningLevel.MINIMAL, ReasoningLevel.HIGH), ReasoningLevel.MINIMAL);
            case "google/gemini-3-pro-image", "google/gemini-3-pro-image-preview" -> ReasoningOptions.of(List.of(ReasoningLevel.MEDIUM));
            default -> null;
        });
    }
}
