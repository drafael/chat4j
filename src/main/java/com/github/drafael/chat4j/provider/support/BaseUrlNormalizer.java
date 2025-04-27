package com.github.drafael.chat4j.provider.support;

public final class BaseUrlNormalizer {

    private static final String ANTHROPIC_DEFAULT_BASE_URL = "https://api.anthropic.com";

    private BaseUrlNormalizer() {
    }

    public static String normalizeAnthropicBaseUrl(String configuredBaseUrl) {
        String normalized = normalize(configuredBaseUrl, ANTHROPIC_DEFAULT_BASE_URL);
        return normalized.endsWith("/v1")
                ? normalized.substring(0, normalized.length() - 3)
                : normalized;
    }

    public static String normalize(String configuredBaseUrl, String defaultBaseUrl) {
        if (configuredBaseUrl == null) {
            return defaultBaseUrl;
        }

        String trimmed = configuredBaseUrl.trim();
        if (trimmed.isEmpty()) {
            return defaultBaseUrl;
        }

        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
