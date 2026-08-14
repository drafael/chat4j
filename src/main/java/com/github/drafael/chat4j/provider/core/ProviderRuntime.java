package com.github.drafael.chat4j.provider.core;

import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.api.ProviderDiagnosticSanitizer;

import java.util.List;

import static java.util.Collections.emptyList;

public record ProviderRuntime(
    ProviderDescriptor descriptor,
    String credentialEnvVar,
    String baseUrl,
    String apiKey,
    String selectedModel,
    List<String> selectedModelSupportedEndpoints,
    String normalizedDefaultBaseUrl
) {

    public ProviderRuntime(
            ProviderDescriptor descriptor,
            String credentialEnvVar,
            String baseUrl,
            String apiKey,
            String selectedModel
    ) {
        this(
                descriptor,
                credentialEnvVar,
                baseUrl,
                apiKey,
                selectedModel,
                emptyList(),
                descriptor == null ? null : descriptor.normalizeBaseUrl(descriptor.defaultBaseUrl())
        );
    }

    public ProviderRuntime(
            ProviderDescriptor descriptor,
            String credentialEnvVar,
            String baseUrl,
            String apiKey,
            String selectedModel,
            List<String> selectedModelSupportedEndpoints
    ) {
        this(
                descriptor,
                credentialEnvVar,
                baseUrl,
                apiKey,
                selectedModel,
                selectedModelSupportedEndpoints,
                descriptor == null ? null : descriptor.normalizeBaseUrl(descriptor.defaultBaseUrl())
        );
    }

    public ProviderRuntime {
        selectedModelSupportedEndpoints = selectedModelSupportedEndpoints == null
                ? emptyList()
                : List.copyOf(selectedModelSupportedEndpoints);
    }

    @Override
    public String toString() {
        return "ProviderRuntime[descriptor=%s, credentialEnvVar=%s, baseUrl=%s, apiKey=<masked>, selectedModel=%s, selectedModelSupportedEndpoints=%s, normalizedDefaultBaseUrl=%s]"
                .formatted(
                        descriptor,
                        credentialEnvVar,
                        ProviderDiagnosticSanitizer.safeOrigin(baseUrl),
                        selectedModel,
                        selectedModelSupportedEndpoints,
                        ProviderDiagnosticSanitizer.safeOrigin(normalizedDefaultBaseUrl)
                );
    }
}
