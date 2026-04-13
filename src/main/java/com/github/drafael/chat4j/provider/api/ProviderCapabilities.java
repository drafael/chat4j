package com.github.drafael.chat4j.provider.api;

public record ProviderCapabilities(
    boolean supportsStreamingChat,
    boolean supportsModelListing,
    boolean supportsImageInput,
    boolean supportsFileInput
) {

    public ProviderCapabilities(boolean supportsStreamingChat, boolean supportsModelListing) {
        this(supportsStreamingChat, supportsModelListing, false, false);
    }

    public static ProviderCapabilities chatAndModels() {
        return new ProviderCapabilities(true, true, false, false);
    }

    public static ProviderCapabilities chatModelsAndImages() {
        return new ProviderCapabilities(true, true, true, false);
    }
}
