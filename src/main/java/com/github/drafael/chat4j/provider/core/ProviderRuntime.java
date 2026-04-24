package com.github.drafael.chat4j.provider.core;

import com.github.drafael.chat4j.provider.api.ProviderDescriptor;

import java.util.List;

import static java.util.Collections.emptyList;

public record ProviderRuntime(
    ProviderDescriptor descriptor,
    String credentialEnvVar,
    String baseUrl,
    String apiKey,
    String selectedModel,
    List<String> selectedModelSupportedEndpoints
) {

    public ProviderRuntime(
            ProviderDescriptor descriptor,
            String credentialEnvVar,
            String baseUrl,
            String apiKey,
            String selectedModel
    ) {
        this(descriptor, credentialEnvVar, baseUrl, apiKey, selectedModel, emptyList());
    }

    public ProviderRuntime {
        selectedModelSupportedEndpoints = selectedModelSupportedEndpoints == null
                ? emptyList()
                : List.copyOf(selectedModelSupportedEndpoints);
    }
}
