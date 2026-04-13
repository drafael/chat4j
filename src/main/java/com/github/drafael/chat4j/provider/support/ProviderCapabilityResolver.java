package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ProviderCapabilities;

import java.util.Locale;
import java.util.Set;

public final class ProviderCapabilityResolver {

    private static final Set<String> IMAGE_PROVIDER_HINTS = Set.of("anthropic", "openai", "google ai", "openrouter");
    private static final Set<String> IMAGE_MODEL_ALLOW_HINTS = Set.of(
            "vision", "gpt-4o", "gpt-4.1", "gpt-4.5", "gemini", "claude", "sonnet", "opus", "haiku", "llava"
    );
    private static final Set<String> IMAGE_MODEL_DENY_HINTS = Set.of("codex", "whisper", "embedding", "moderation", "tts");

    private ProviderCapabilityResolver() {
    }

    public static boolean supportsImageInput(ProviderCapabilities capabilities, String providerName, String modelId) {
        String provider = normalize(providerName);
        String model = normalize(modelId);

        if (containsAny(model, IMAGE_MODEL_DENY_HINTS)) {
            return false;
        }

        if (capabilities != null && capabilities.supportsImageInput()) {
            return true;
        }

        boolean providerHinted = containsAny(provider, IMAGE_PROVIDER_HINTS);
        if (!providerHinted) {
            return false;
        }

        return !model.isBlank() && containsAny(model, IMAGE_MODEL_ALLOW_HINTS);
    }

    public static boolean supportsFileInput(ProviderCapabilities capabilities, String providerName, String modelId) {
        if (capabilities != null && capabilities.supportsFileInput()) {
            return true;
        }

        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static boolean containsAny(String value, Set<String> hints) {
        return hints.stream().anyMatch(value::contains);
    }
}
