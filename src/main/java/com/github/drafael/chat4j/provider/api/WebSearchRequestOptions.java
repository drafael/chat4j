package com.github.drafael.chat4j.provider.api;

public record WebSearchRequestOptions(boolean enabled, boolean capabilityEvidenceAdmitted) {

    public WebSearchRequestOptions(boolean enabled) {
        this(enabled, false);
    }

    public static WebSearchRequestOptions disabled() {
        return new WebSearchRequestOptions(false);
    }
}
