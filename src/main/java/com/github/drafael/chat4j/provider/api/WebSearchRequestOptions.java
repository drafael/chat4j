package com.github.drafael.chat4j.provider.api;

public record WebSearchRequestOptions(boolean enabled) {

    public static WebSearchRequestOptions disabled() {
        return new WebSearchRequestOptions(false);
    }
}
