package com.github.drafael.chat4j.provider.api;

public record ProviderCapabilities(
    boolean supportsStreamingChat,
    boolean supportsModelListing
) {

    public static ProviderCapabilities chatAndModels() {
        return new ProviderCapabilities(true, true);
    }
}
