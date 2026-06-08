package com.github.drafael.chat4j.chat.model;

import com.github.drafael.chat4j.provider.api.ProviderCapabilities;

public record ProviderSelectionSnapshot(
        String providerName,
        String modelId,
        ProviderCapabilities capabilities,
        String baseUrl,
        String apiKey
) {

    @Override
    public String toString() {
        return "ProviderSelectionSnapshot[providerName=%s, modelId=%s, capabilities=%s, baseUrl=%s, apiKey=<masked>]"
                .formatted(providerName, modelId, capabilities, baseUrl);
    }
}
