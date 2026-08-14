package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class MistralNativeWebSearchSupport {

    private static final String PROVIDER_NAME = "Mistral";
    private static final String CANONICAL_BASE_URL = "https://api.mistral.ai/v1";
    private static final String CONVERSATIONS_URL = "https://api.mistral.ai/v1/conversations";

    private static final Set<String> SUPPORTED_PATHS = Set.of("", "/", "/v1", "/v1/");
    private static final Set<String> CONNECTOR_CAPABLE_MODEL_PREFIXES = Set.of(
            "mistral-small",
            "mistral-medium",
            "mistral-large"
    );
    private static final Set<String> NON_CHAT_MODEL_MARKERS = Set.of(
            "embed",
            "moderat",
            "ocr",
            "transcrib",
            "voxtral",
            "realtime",
            "audio",
            "speech",
            "tts",
            "image",
            "flux"
    );

    private MistralNativeWebSearchSupport() {
    }

    static boolean isMistral(String providerName) {
        return Strings.CS.equals(providerName, PROVIDER_NAME);
    }

    public static boolean supports(String providerName, String modelId, String baseUrl) {
        return isMistral(providerName) && supportsModel(modelId) && canonicalBaseUrl(baseUrl).isPresent();
    }

    static boolean supportsModel(String modelId) {
        String normalized = StringUtils.trimToEmpty(modelId).toLowerCase(Locale.ROOT);
        return isChatModel(normalized) && CONNECTOR_CAPABLE_MODEL_PREFIXES.stream()
                .anyMatch(prefix -> normalized.equals(prefix) || normalized.startsWith("%s-".formatted(prefix)));
    }

    public static boolean isChatModel(String modelId) {
        String normalized = StringUtils.trimToEmpty(modelId).toLowerCase(Locale.ROOT);
        return StringUtils.isNotBlank(normalized)
                && NON_CHAT_MODEL_MARKERS.stream().noneMatch(normalized::contains);
    }

    private static Optional<String> canonicalBaseUrl(String baseUrl) {
        if (StringUtils.isBlank(baseUrl)) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            if (!Strings.CI.equals(uri.getScheme(), "https")
                    || !Strings.CI.equals(uri.getHost(), "api.mistral.ai")
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !SUPPORTED_PATHS.contains(StringUtils.defaultString(uri.getRawPath()))) {
                return Optional.empty();
            }
            return Optional.of(CANONICAL_BASE_URL);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static Optional<URI> conversationsUri(String baseUrl) {
        return canonicalBaseUrl(baseUrl).map(ignored -> URI.create(CONVERSATIONS_URL));
    }
}
