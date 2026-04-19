package com.github.drafael.chat4j.provider.api;

import java.util.List;
import java.util.function.UnaryOperator;
import static java.util.Collections.emptyList;

public record ProviderDescriptor(
    String name,
    AuthType authType,
    String credentialEnvVar,
    String fallbackApiKey,
    OAuthCliSpec oauthCliSpec,
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
}
