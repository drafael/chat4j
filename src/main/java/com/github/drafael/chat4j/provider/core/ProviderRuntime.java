package com.github.drafael.chat4j.provider.core;

import com.github.drafael.chat4j.provider.api.ProviderDescriptor;

public record ProviderRuntime(
    ProviderDescriptor descriptor,
    String credentialEnvVar,
    String baseUrl,
    String apiKey,
    String selectedModel
) {
}
