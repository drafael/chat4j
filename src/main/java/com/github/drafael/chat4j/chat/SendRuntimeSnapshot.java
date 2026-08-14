package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.support.NativeWebSearchOutcome;

import java.util.Objects;

record SendRuntimeSnapshot(
        ProviderRegistry.ProviderDef providerDefinition,
        String modelId,
        NativeWebSearchOutcome webSearchOutcome
) {
    SendRuntimeSnapshot {
        Objects.requireNonNull(providerDefinition, "providerDefinition should not be null");
        Objects.requireNonNull(webSearchOutcome, "webSearchOutcome should not be null");
    }

    String providerName() {
        return providerDefinition.name();
    }

    ProviderCapabilities capabilities() {
        return providerDefinition.capabilities();
    }

    String baseUrl() {
        return providerDefinition.baseUrl();
    }
}
