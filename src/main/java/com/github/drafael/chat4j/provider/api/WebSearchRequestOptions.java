package com.github.drafael.chat4j.provider.api;

public record WebSearchRequestOptions(boolean enabled, String optionId) {

    public WebSearchRequestOptions {
        optionId = optionId == null ? "" : optionId;
    }

    public static WebSearchRequestOptions disabled() {
        return new WebSearchRequestOptions(false, "");
    }
}
