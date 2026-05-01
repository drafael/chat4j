package com.github.drafael.chat4j.web;

import java.util.List;

import static java.util.Collections.emptyList;

/**
 * Holds separate internal web-search metadata.
 * responses are provider search results returned by the search API.
 * browsedPages are the subset Chat4J fetched and parsed.
 * The chat UI merges both into one Sources list, while this record keeps the distinction for prompts and future metadata.
 */
public record WebSearchContext(
        String promptContext,
        List<WebSearchResponse> responses,
        List<BrowsedPage> browsedPages
) {

    public WebSearchContext {
        promptContext = promptContext == null ? "" : promptContext;
        responses = responses == null ? emptyList() : List.copyOf(responses);
        browsedPages = browsedPages == null ? emptyList() : List.copyOf(browsedPages);
    }
}
