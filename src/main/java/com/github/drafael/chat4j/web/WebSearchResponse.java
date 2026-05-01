package com.github.drafael.chat4j.web;

import java.util.List;

import static java.util.Collections.emptyList;

public record WebSearchResponse(String query, String answer, List<WebSearchResult> results) {

    public WebSearchResponse {
        results = results == null ? emptyList() : List.copyOf(results);
    }
}
