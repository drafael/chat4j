package com.github.drafael.chat4j.web;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toMap;

public class WebSearchCoordinator {

    private final Map<String, WebSearchProvider> providers;
    private final WebQueryPlanner queryPlanner;
    private final WebPageFetcher pageFetcher;
    private final WebContextPromptBuilder promptBuilder = new WebContextPromptBuilder();

    public WebSearchCoordinator(List<WebSearchProvider> providers) {
        this(providers, new RawPromptWebQueryPlanner(), new JsoupWebPageFetcher());
    }

    public WebSearchCoordinator(List<WebSearchProvider> providers, WebPageFetcher pageFetcher) {
        this(providers, new RawPromptWebQueryPlanner(), pageFetcher);
    }

    public WebSearchCoordinator(List<WebSearchProvider> providers, WebQueryPlanner queryPlanner, WebPageFetcher pageFetcher) {
        List<WebSearchProvider> safeProviders = providers == null ? emptyList() : providers;
        this.providers = safeProviders.stream().collect(toMap(WebSearchProvider::id, provider -> provider));
        this.queryPlanner = queryPlanner == null ? new RawPromptWebQueryPlanner() : queryPlanner;
        this.pageFetcher = pageFetcher == null ? new JsoupWebPageFetcher() : pageFetcher;
    }

    public WebSearchContext buildExternalContextDetails(
            String providerId,
            String query,
            int resultCount,
            BooleanSupplier isCancelled,
            WebQueryPlanner planner
    ) throws Exception {
        WebSearchProvider provider = providers.get(providerId);
        if (provider == null || !provider.available() || shouldStop(isCancelled)) {
            return new WebSearchContext("", emptyList(), emptyList());
        }

        List<String> queries = plannedQueries(query, isCancelled, planner);
        if (queries.isEmpty()) {
            return new WebSearchContext("", emptyList(), emptyList());
        }

        List<WebSearchResponse> responses = search(provider, queries, resultCount, isCancelled);
        List<BrowsedPage> browsedPages = browseTopResults(responses, resultCount, isCancelled);
        return new WebSearchContext(promptBuilder.build(responses, browsedPages), responses, browsedPages);
    }

    private List<String> plannedQueries(String query, BooleanSupplier isCancelled, WebQueryPlanner planner) {
        try {
            WebQueryPlanner effectivePlanner = planner == null ? queryPlanner : planner;
            List<String> planned = effectivePlanner.planQueries(query, isCancelled);
            List<String> normalized = planned == null ? emptyList() : planned.stream()
                    .map(StringUtils::trimToEmpty)
                    .filter(StringUtils::isNotBlank)
                    .distinct()
                    .limit(3)
                    .toList();
            if (!normalized.isEmpty()) {
                return normalized;
            }
        } catch (Exception ignored) {
            // Fall back to the raw prompt below.
        }

        String fallback = StringUtils.trimToEmpty(query);
        return StringUtils.isBlank(fallback) || shouldStop(isCancelled) ? emptyList() : List.of(fallback);
    }

    private List<WebSearchResponse> search(
            WebSearchProvider provider,
            List<String> queries,
            int resultCount,
            BooleanSupplier isCancelled
    ) throws Exception {
        List<WebSearchResponse> responses = new ArrayList<>();
        for (String plannedQuery : queries) {
            if (shouldStop(isCancelled)) {
                break;
            }
            responses.add(provider.search(new WebSearchRequest(plannedQuery, resultCount), isCancelled));
        }
        return List.copyOf(responses);
    }

    private List<BrowsedPage> browseTopResults(
            List<WebSearchResponse> responses,
            int resultCount,
            BooleanSupplier isCancelled
    ) {
        if (responses == null || responses.isEmpty() || shouldStop(isCancelled)) {
            return emptyList();
        }

        return responses.stream()
                .flatMap(response -> response.results().stream())
                .map(WebSearchResult::url)
                .filter(StringUtils::isNotBlank)
                .collect(toMap(
                        url -> normalizeUrlForDedupe(url),
                        url -> url,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ))
                .values()
                .stream()
                .limit(Math.max(1, resultCount))
                .takeWhile(ignored -> !shouldStop(isCancelled))
                .map(url -> pageFetcher.fetch(url, isCancelled))
                .toList();
    }

    private String normalizeUrlForDedupe(String url) {
        String normalized = StringUtils.defaultString(url).trim();
        while (normalized.endsWith("/") || normalized.endsWith("#")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean shouldStop(BooleanSupplier isCancelled) {
        return Thread.currentThread().isInterrupted() || (isCancelled != null && isCancelled.getAsBoolean());
    }
}
