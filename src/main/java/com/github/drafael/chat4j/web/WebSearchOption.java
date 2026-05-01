package com.github.drafael.chat4j.web;

public record WebSearchOption(
        String id,
        String label,
        WebSearchMode mode,
        boolean available
) {

    public boolean nativeMode() {
        return mode == WebSearchMode.NATIVE;
    }

    public boolean externalMode() {
        return mode == WebSearchMode.EXTERNAL;
    }
}
