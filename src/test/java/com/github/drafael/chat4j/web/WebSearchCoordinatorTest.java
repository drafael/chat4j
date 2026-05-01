package com.github.drafael.chat4j.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchCoordinatorTest {

    @Test
    @DisplayName("External web search browses top unique result URLs and includes page excerpts")
    void buildExternalContextDetails_whenResultsContainDuplicateUrls_browsesUniquePages() throws Exception {
        WebSearchProvider provider = new WebSearchProvider() {
            @Override
            public String id() {
                return "test";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public WebSearchResponse search(WebSearchRequest request) {
                return new WebSearchResponse(
                        request.query(),
                        "answer",
                        List.of(
                                new WebSearchResult("One", "https://example.test/one", "example.test", "s1"),
                                new WebSearchResult("One duplicate", "https://example.test/one", "example.test", "s1"),
                                new WebSearchResult("Two", "https://example.test/two", "example.test", "s2")
                        )
                );
            }
        };
        WebPageFetcher fetcher = (url, isCancelled) -> new BrowsedPage(
                url,
                url,
                "example.test",
                "excerpt for %s".formatted(url),
                true,
                ""
        );
        WebSearchCoordinator subject = new WebSearchCoordinator(List.of(provider), fetcher);

        WebSearchContext context = subject.buildExternalContextDetails("test", "query", 2, () -> false, new RawPromptWebQueryPlanner());

        assertThat(context.responses()).hasSize(1);
        assertThat(context.browsedPages()).extracting(BrowsedPage::url)
                .containsExactly("https://example.test/one", "https://example.test/two");
        assertThat(context.promptContext()).contains("Browsed pages:").contains("excerpt for https://example.test/one");
    }

    @Test
    @DisplayName("External web search skips browsing when cancellation is requested")
    void buildExternalContextDetails_whenCancelled_skipsBrowsing() throws Exception {
        WebSearchProvider provider = new WebSearchProvider() {
            @Override
            public String id() {
                return "test";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public WebSearchResponse search(WebSearchRequest request) {
                return new WebSearchResponse(
                        request.query(),
                        "answer",
                        List.of(new WebSearchResult("One", "https://example.test/one", "example.test", "s1"))
                );
            }
        };
        WebSearchCoordinator subject = new WebSearchCoordinator(List.of(provider), failingFetcher());

        WebSearchContext context = subject.buildExternalContextDetails("test", "query", 2, () -> true, new RawPromptWebQueryPlanner());

        assertThat(context.browsedPages()).isEmpty();
    }

    private WebPageFetcher failingFetcher() {
        return new WebPageFetcher() {
            @Override
            public BrowsedPage fetch(String url, BooleanSupplier isCancelled) {
                throw new AssertionError("fetcher should not be called");
            }
        };
    }
}
