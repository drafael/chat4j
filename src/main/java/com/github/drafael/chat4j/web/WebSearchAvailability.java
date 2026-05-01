package com.github.drafael.chat4j.web;

import java.util.List;

import static java.util.Collections.emptyList;

public record WebSearchAvailability(
        List<WebSearchOption> options,
        String defaultOptionId
) {

    public WebSearchAvailability {
        options = options == null ? emptyList() : List.copyOf(options);
    }

    public boolean available() {
        return options.stream().anyMatch(WebSearchOption::available);
    }
}
