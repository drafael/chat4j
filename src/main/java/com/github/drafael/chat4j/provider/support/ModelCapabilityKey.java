package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ProviderDiagnosticSanitizer;

record ModelCapabilityKey(String provider, String baseUrl, String modelId, String authFingerprint) {
    @Override
    public String toString() {
        return "ModelCapabilityKey[provider=%s, baseUrl=%s, modelId=%s, authFingerprint=<masked>]"
                .formatted(provider, ProviderDiagnosticSanitizer.safeOrigin(baseUrl), modelId);
    }
}
