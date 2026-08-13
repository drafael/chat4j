package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class DeepSeekNativeWebSearchSupport {

    public static final String PROVIDER_NAME = "DeepSeek";
    public static final String CANONICAL_BASE_URL = "https://api.deepseek.com";
    public static final String ANTHROPIC_BASE_URL = "https://api.deepseek.com/anthropic";

    private static final Set<String> SUPPORTED_MODELS = Set.of(
            "deepseek-v4-flash",
            "deepseek-v4-pro",
            "deepseek-v4-pro[1m]"
    );
    private static final Set<String> SUPPORTED_PATHS = Set.of("", "/", "/v1", "/v1/");

    private DeepSeekNativeWebSearchSupport() {
    }

    public static boolean isDeepSeek(String providerName) {
        return Strings.CI.equals(StringUtils.trimToEmpty(providerName), PROVIDER_NAME);
    }

    public static boolean supports(String providerName, String modelId, String baseUrl) {
        return isDeepSeek(providerName) && supportsModel(modelId) && canonicalBaseUrl(baseUrl).isPresent();
    }

    public static boolean supportsModel(String modelId) {
        return SUPPORTED_MODELS.contains(StringUtils.trimToEmpty(modelId).toLowerCase(Locale.ROOT));
    }

    public static Optional<String> canonicalBaseUrl(String baseUrl) {
        if (StringUtils.isBlank(baseUrl)) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            if (!Strings.CI.equals(uri.getScheme(), "https")
                    || !Strings.CI.equals(uri.getHost(), "api.deepseek.com")
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !SUPPORTED_PATHS.contains(StringUtils.defaultString(uri.getRawPath()))
            ) {
                return Optional.empty();
            }
            return Optional.of(CANONICAL_BASE_URL);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static Optional<String> anthropicBaseUrl(String baseUrl) {
        return canonicalBaseUrl(baseUrl).map(ignored -> ANTHROPIC_BASE_URL);
    }
}
