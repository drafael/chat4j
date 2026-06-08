package com.github.drafael.chat4j.chat.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchActivityNormalizerTest {

    @Test
    @DisplayName("Repeated web search sections are merged and de-duplicated")
    void normalize_whenActivityContainsRepeatedSections_mergesDuplicateQueriesAndSources() {
        String activity = """
                **Searched**
                - what's new in java 25

                **Visited sources**
                - https://www.oracle.com/java/technologies/javase/25-relnotes.html

                **Searched**
                - what's new in java 25

                **Visited sources**
                - Native web search is handled by Anthropic (claude-haiku-4-5-20251001). Source URLs will appear here if the provider returns citation metadata in the answer.

                **Searched**
                - what's new in java 25

                **Visited sources**
                - Native web search is handled by Anthropic (claude-haiku-4-5-20251001). Source URLs will appear here if the provider returns citation metadata in the answer.
                """;

        String normalized = WebSearchActivityNormalizer.normalize(activity);

        assertThat(normalized).isEqualTo("""
                **Searched**
                - what's new in java 25

                **Sources**
                - https://www.oracle.com/java/technologies/javase/25-relnotes.html
                """.trim());
    }

    @Test
    @DisplayName("Markdown link sources are de-duplicated by URL")
    void normalize_whenSourcesUseDifferentLabels_deduplicatesByUrl() {
        String activity = """
                **Visited sources**
                1. [Oracle Java 25](https://example.test/java25) — release notes
                2. [Duplicate title](https://example.test/java25) — duplicate

                **Browsed pages**
                1. [Oracle Java 25](https://example.test/java25) — excerpt
                2. [Oracle Java 25](https://example.test/java25/) — duplicate with slash
                """;

        String normalized = WebSearchActivityNormalizer.normalize(activity);

        assertThat(normalized).isEqualTo("""
                **Sources**
                - [Oracle Java 25](https://example.test/java25) — release notes
                """.trim());
    }
}
