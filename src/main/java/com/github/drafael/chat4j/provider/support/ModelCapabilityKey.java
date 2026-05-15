package com.github.drafael.chat4j.provider.support;

record ModelCapabilityKey(String provider, String baseUrl, String modelId, String authFingerprint) {
    @Override
    public String toString() {
        return "ModelCapabilityKey[provider=%s, baseUrl=%s, modelId=%s, authFingerprint=<masked>]"
                .formatted(provider, baseUrl, modelId);
    }
}
