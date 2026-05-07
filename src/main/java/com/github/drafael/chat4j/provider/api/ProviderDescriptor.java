package com.github.drafael.chat4j.provider.api;

import java.util.List;
import java.util.function.UnaryOperator;
import static java.util.Collections.emptyList;

public record ProviderDescriptor(
    String name,
    AuthType authType,
    String credentialEnvVar,
    String fallbackApiKey,
    String defaultBaseUrl,
    List<String> seedModels,
    ProviderCapabilities capabilities,
    UnaryOperator<String> baseUrlNormalizer
) {

    public ProviderDescriptor {
        authType = authType == null ? AuthType.ENV_VAR : authType;
        seedModels = seedModels == null ? emptyList() : List.copyOf(seedModels);
        capabilities = capabilities == null ? ProviderCapabilities.chatAndModels() : capabilities;
        baseUrlNormalizer = baseUrlNormalizer == null ? UnaryOperator.identity() : baseUrlNormalizer;
    }

    public String normalizeBaseUrl(String configuredBaseUrl) {
        return baseUrlNormalizer.apply(configuredBaseUrl);
    }

    @Override
    public String toString() {
        return "ProviderDescriptor[name=%s, authType=%s, credentialEnvVar=%s, fallbackApiKey=<masked>, defaultBaseUrl=%s, seedModels=%s, capabilities=%s, baseUrlNormalizer=%s]"
                .formatted(name, authType, credentialEnvVar, defaultBaseUrl, seedModels, capabilities, baseUrlNormalizer);
    }
}
