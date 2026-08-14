package com.github.drafael.chat4j.provider.api;

public record ProviderCapabilities(
    boolean supportsImageInput,
    boolean supportsFileInput
) {

    public static ProviderCapabilities chatAndModels() {
        return new ProviderCapabilities(false, false);
    }

    public static ProviderCapabilities chatModelsAndImages() {
        return new ProviderCapabilities(true, false);
    }

    public static ProviderCapabilities chatModelsImagesAndFiles() {
        return new ProviderCapabilities(true, true);
    }
}
