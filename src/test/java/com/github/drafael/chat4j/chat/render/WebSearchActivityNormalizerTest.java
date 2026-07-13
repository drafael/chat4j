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
    @DisplayName("Native web search placeholder sources are not persisted without real source URLs")
    void normalize_whenNativePlaceholderHasNoRealSources_removesPlaceholderSource() {
        String activity = """
                **Searched**
                - jna examples

                **Sources**
                - Native web search is handled by Google AI (gemini-3.1-pro-preview). Source URLs will appear here if the provider returns citation metadata in the answer.
                """;

        String normalized = WebSearchActivityNormalizer.normalize(activity);

        assertThat(normalized).isEqualTo("""
                **Searched**
                - jna examples
                """.trim());
    }

    @Test
    @DisplayName("Bold headings with trailing colon are recognized")
    void normalize_whenBoldHeadingsContainColon_recognizesSections() {
        String activity = """
                **Searched:**
                - jna examples

                **Sources:**
                - https://github.com/java-native-access/jna
                """;

        String normalized = WebSearchActivityNormalizer.normalize(activity);

        assertThat(normalized).isEqualTo("""
                **Searched**
                - jna examples

                **Sources**
                - https://github.com/java-native-access/jna
                """.trim());
    }

    @Test
    @DisplayName("Bold headings with colon after bold marker are recognized")
    void normalize_whenBoldHeadingsHaveColonAfterBoldMarker_recognizesSections() {
        String activity = """
                **Searched**:
                - jna examples

                **Sources**:
                - https://github.com/java-native-access/jna
                """;

        String normalized = WebSearchActivityNormalizer.normalize(activity);

        assertThat(normalized).isEqualTo("""
                **Searched**
                - jna examples

                **Sources**
                - https://github.com/java-native-access/jna
                """.trim());
    }

    @Test
    @DisplayName("Angle-bracket source URLs preserve literal parentheses")
    void normalize_whenAngleBracketSourceUrlContainsParentheses_deduplicatesUsingFullUrl() {
        String activity = """
                **Sources**
                - [Wikipedia Source](<https://example.com/Foo_(bar)>)
                - <https://example.com/Foo_(bar)>
                """;

        String normalized = WebSearchActivityNormalizer.normalize(activity);

        assertThat(normalized).isEqualTo("""
                **Sources**
                - [Wikipedia Source](<https://example.com/Foo_(bar)>)
                """.trim());
    }

    @Test
    @DisplayName("Non-angle markdown source URLs preserve balanced parentheses")
    void normalize_whenNonAngleMarkdownSourceUrlContainsParentheses_deduplicatesUsingFullUrl() {
        String activity = """
                **Sources**
                - [Wikipedia Source](https://example.com/Foo_(bar))
                - <https://example.com/Foo_(bar)>
                """;

        String normalized = WebSearchActivityNormalizer.normalize(activity);

        assertThat(normalized).isEqualTo("""
                **Sources**
                - [Wikipedia Source](https://example.com/Foo_(bar))
                """.trim());
    }

    @Test
    @DisplayName("Raw source URLs preserve balanced parentheses")
    void normalize_whenRawSourceUrlContainsParentheses_deduplicatesUsingFullUrl() {
        String activity = """
                **Sources**
                - https://example.com/Foo_(bar)
                - <https://example.com/Foo_(bar)>
                """;

        String normalized = WebSearchActivityNormalizer.normalize(activity);

        assertThat(normalized).isEqualTo("""
                **Sources**
                - https://example.com/Foo_(bar)
                """.trim());
    }

    @Test
    @DisplayName("Labeled source links replace raw URLs for the same source")
    void normalize_whenRawUrlAppearsBeforeLabeledLink_prefersReadableLabel() {
        String activity = """
                **Sources**
                - <https://example.com/source>
                - [Readable Source](<https://example.com/source>)
                """;

        String normalized = WebSearchActivityNormalizer.normalize(activity);

        assertThat(normalized).isEqualTo("""
                **Sources**
                - [Readable Source](<https://example.com/source>)
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
