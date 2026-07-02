package com.github.drafael.chat4j.provider.api;

public record ProviderCapabilities(
    boolean supportsStreamingChat,
    boolean supportsModelListing,
    boolean supportsImageInput,
    boolean supportsFileInput,
    boolean supportsNativeWebSearch,
    boolean supportsExternalWebSearchProvider
) {

    public ProviderCapabilities(
            boolean supportsStreamingChat,
            boolean supportsModelListing,
            boolean supportsImageInput,
            boolean supportsFileInput
    ) {
        this(supportsStreamingChat, supportsModelListing, supportsImageInput, supportsFileInput, false, false);
    }

    public ProviderCapabilities(boolean supportsStreamingChat, boolean supportsModelListing) {
        this(supportsStreamingChat, supportsModelListing, false, false, false, false);
    }

    public static ProviderCapabilities chatAndModels() {
        return new ProviderCapabilities(true, true, false, false, false, false);
    }

    public static ProviderCapabilities chatModelsAndImages() {
        return new ProviderCapabilities(true, true, true, false, false, false);
    }

    public static ProviderCapabilities chatModelsImagesAndFiles() {
        return new ProviderCapabilities(true, true, true, true, false, false);
    }

    public static ProviderCapabilities chatModelsAndNativeWebSearch() {
        return new ProviderCapabilities(true, true, false, false, true, true);
    }
}
