package com.github.drafael.chat4j.chat.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReadAloudTextExtractorTest {

    @Test
    @DisplayName("Prose formatting becomes natural speech text")
    void extract_whenMarkdownContainsProseFormatting_preservesReadableContent() {
        String markdown = """
                ## Title
                - This is **bold** with [a link](https://example.com).
                - Use `Thread.startVirtualThread()` and keep $5.
                - Preserve ``a `$x$ b`` as inline code.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result).isEqualTo(
                "Title This is bold with a link. Use Thread.startVirtualThread() and keep $5. "
                        + "Preserve a `$x$ b as inline code."
        );
    }

    @Test
    @DisplayName("Fenced blocks and diagrams are silently omitted")
    void extract_whenMarkdownContainsFencedBlocks_omitsCompleteBlocks() {
        String markdown = """
                Before.
                ````java
                CODE_SENTINEL
                ```
                STILL_CODE_SENTINEL
                ````
                Between.
                ~~~mermaid
                MERMAID_SENTINEL
                ~~~
                ~~~~smiles
                SMILES_SENTINEL
                ~~~~
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .isEqualTo("Before. Between. After.")
                .doesNotContain("CODE_SENTINEL", "STILL_CODE_SENTINEL", "MERMAID_SENTINEL", "SMILES_SENTINEL");
    }

    @Test
    @DisplayName("Fences nested in list and blockquote containers are omitted")
    void extract_whenFencesAreNestedInContainers_omitsCompleteBlocks() {
        String markdown = """
                Before.
                - ```java
                  LIST_CODE_SENTINEL
                  ```
                - List prose.

                    ~~~~mermaid
                    LIST_CONTINUATION_DIAGRAM_SENTINEL
                    ~~~~
                  - ```java
                    NESTED_LIST_CODE_SENTINEL
                    ```
                  - Preserved nested prose.
                    - ~~~java
                      DEEP_NESTED_LIST_CODE_SENTINEL
                      ~~~
                    Preserved dedented nested prose.
                    - Preserved deep nested prose.
                > ~~~mermaid
                > BLOCKQUOTE_DIAGRAM_SENTINEL
                > ~~~
                - > ~~~mermaid
                  > LIST_BLOCKQUOTE_DIAGRAM_SENTINEL
                  > ~~~
                > - ~~~smiles
                >   BLOCKQUOTE_LIST_DIAGRAM_SENTINEL
                >   ~~~
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains(
                        "Before.",
                        "List prose.",
                        "Preserved nested prose.",
                        "Preserved dedented nested prose.",
                        "Preserved deep nested prose.",
                        "After."
                )
                .doesNotContain(
                        "LIST_CODE_SENTINEL",
                        "LIST_CONTINUATION_DIAGRAM_SENTINEL",
                        "NESTED_LIST_CODE_SENTINEL",
                        "DEEP_NESTED_LIST_CODE_SENTINEL",
                        "BLOCKQUOTE_DIAGRAM_SENTINEL",
                        "LIST_BLOCKQUOTE_DIAGRAM_SENTINEL",
                        "BLOCKQUOTE_LIST_DIAGRAM_SENTINEL"
                );
    }

    @Test
    @DisplayName("Leaving a fence container preserves prose in the new container")
    void extract_whenFenceUsesMixedContainers_preservesProseOutsideEachFence() {
        String markdown = """
                - > ~~~mermaid
                  > LIST_QUOTE_CODE_SENTINEL
                >   ~~~
                WRONG_CONTAINER_STILL_CODE_SENTINEL
                  > ~~~
                - Preserved after list quote.
                > - ~~~smiles
                >   QUOTE_LIST_CODE_SENTINEL
                >   ~~~
                > Preserved after quote list.
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains(
                        "WRONG_CONTAINER_STILL_CODE_SENTINEL",
                        "Preserved after list quote.",
                        "Preserved after quote list.",
                        "After."
                )
                .doesNotContain("LIST_QUOTE_CODE_SENTINEL", "QUOTE_LIST_CODE_SENTINEL");
    }

    @Test
    @DisplayName("Blockquote fence indentation does not become a closing requirement")
    void extract_whenListBlockquoteFenceHasOptionalIndent_acceptsShallowerValidCloser() {
        String markdown = """
                - List item.
                  >   ~~~mermaid
                  > BLOCKQUOTE_CODE_SENTINEL
                  >     ~~~
                  > STILL_BLOCKQUOTE_CODE_SENTINEL
                  > ~~~
                  > Preserved quoted prose.
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("List item.", "Preserved quoted prose.", "After.")
                .doesNotContain("BLOCKQUOTE_CODE_SENTINEL", "STILL_BLOCKQUOTE_CODE_SENTINEL");
    }

    @Test
    @DisplayName("A list nested later inside a blockquote requires its own fence indentation")
    void extract_whenNestedListStateIsQuoteRelative_rejectsShallowerFenceCloser() {
        String markdown = """
                - outer
                  > - inner
                  >   ~~~java
                  >   NESTED_QUOTE_LIST_CODE_SENTINEL
                  > ~~~
                  > SHALLOW_FENCE_LEAK_SENTINEL
                  >   ~~~
                  > Preserved nested prose.
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("outer", "inner", "Preserved nested prose.", "After.")
                .doesNotContain("NESTED_QUOTE_LIST_CODE_SENTINEL", "SHALLOW_FENCE_LEAK_SENTINEL");
    }

    @Test
    @DisplayName("A top-level blockquote fence does not inherit a preceding list")
    void extract_whenBlockquoteFollowsList_closesAtAnyValidQuoteIndentation() {
        String markdown = """
                - List item.
                >   ~~~mermaid
                >   BLOCKQUOTE_AFTER_LIST_SENTINEL
                > ~~~
                > Preserved quoted prose.
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("List item.", "Preserved quoted prose.", "After.")
                .doesNotContain("BLOCKQUOTE_AFTER_LIST_SENTINEL");
    }

    @Test
    @DisplayName("Fence closers must remain in the opening container")
    void extract_whenFenceDelimiterAppearsInDifferentContainer_keepsCodeExcludedUntilMatchingClose() {
        String markdown = """
                Before.
                ```java
                TOP_LEVEL_CODE_SENTINEL
                > ```
                TOP_LEVEL_STILL_CODE_SENTINEL
                ```
                > ```java
                > QUOTED_CODE_SENTINEL
                ```
                > QUOTED_STILL_CODE_SENTINEL
                > ```
                - ```java
                  LIST_CODE_SENTINEL
                ```
                  LIST_STILL_CODE_SENTINEL
                  ```
                  FINAL_CODE_SENTINEL
                  ```
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("Before.", "LIST_STILL_CODE_SENTINEL", "After.")
                .doesNotContain(
                        "TOP_LEVEL_CODE_SENTINEL",
                        "TOP_LEVEL_STILL_CODE_SENTINEL",
                        "QUOTED_CODE_SENTINEL",
                        "QUOTED_STILL_CODE_SENTINEL",
                        "LIST_CODE_SENTINEL",
                        "FINAL_CODE_SENTINEL"
                );
    }

    @Test
    @DisplayName("List fences accept relative indentation and tab-expanded closing fences")
    void extract_whenListFenceClosersUseValidIndentation_preservesFollowingProse() {
        String markdown = """
                Before.
                - ```java
                    SPACE_INDENTED_CODE_SENTINEL
                    ```
                Between.
                - item
                  ~~~java
                  CONTINUATION_CODE_SENTINEL
                     ~~~
                - ~~~java
                \tTAB_INDENTED_CODE_SENTINEL
                \t~~~
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("Before.", "Between.", "item", "After.")
                .doesNotContain(
                        "SPACE_INDENTED_CODE_SENTINEL",
                        "CONTINUATION_CODE_SENTINEL",
                        "TAB_INDENTED_CODE_SENTINEL"
                );
    }

    @Test
    @DisplayName("Blank lines do not end fenced code or display math inside lists")
    void extract_whenListBlocksContainBlankLines_omitsTheCompleteBlocks() {
        String markdown = """
                - ```java
                  FIRST_LIST_CODE_SENTINEL

                  SECOND_LIST_CODE_SENTINEL
                  ```
                  Preserved after code.
                - $$
                  FIRST_LIST_MATH_SENTINEL = x

                  SECOND_LIST_MATH_SENTINEL = y
                  $$
                  Preserved after math.
                Done.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("Preserved after code.", "Preserved after math.", "Done.")
                .doesNotContain(
                        "FIRST_LIST_CODE_SENTINEL",
                        "SECOND_LIST_CODE_SENTINEL",
                        "FIRST_LIST_MATH_SENTINEL",
                        "SECOND_LIST_MATH_SENTINEL"
                );
    }

    @Test
    @DisplayName("Unclosed nested blocks end with their Markdown container")
    void extract_whenNestedBlockIsUnclosed_preservesProseAfterContainerEnds() {
        String markdown = """
                Before.
                > ```java
                > FENCED_CODE_SENTINEL
                Outside fenced prose.
                > $$
                > DISPLAY_MATH_SENTINEL = x
                Outside math prose.
                - $$
                  LIST_MATH_SENTINEL = y
                > ```java
                > FOLLOWING_FENCE_SENTINEL
                Outside list math and following fence.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains(
                        "Before.",
                        "Outside fenced prose.",
                        "Outside math prose.",
                        "Outside list math and following fence."
                )
                .doesNotContain(
                        "FENCED_CODE_SENTINEL",
                        "DISPLAY_MATH_SENTINEL",
                        "LIST_MATH_SENTINEL",
                        "FOLLOWING_FENCE_SENTINEL"
                );
    }

    @Test
    @DisplayName("Backticks in a fence info string remain ordinary prose")
    void extract_whenBacktickFenceInfoIsInvalid_preservesFollowingProse() {
        String markdown = """
                Before.
                ``` bad`info
                PRESERVED_PROSE_SENTINEL
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result).isEqualTo("Before. bad`info PRESERVED_PROSE_SENTINEL After.");
    }

    @Test
    @DisplayName("An unclosed fence omits the rest of the message")
    void extract_whenFenceIsUnclosed_omitsRemainingContent() {
        String markdown = """
                Keep this.
                ```java
                CODE_SENTINEL
                This must also be omitted.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result).isEqualTo("Keep this.");
    }

    @Test
    @DisplayName("Indented code after a table is omitted")
    void extract_whenIndentedCodeFollowsTable_omitsCodeBlock() {
        String markdown = """
                | Heading |
                | --- |
                    TABLE_CODE_SENTINEL
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("Heading", "After.")
                .doesNotContain("TABLE_CODE_SENTINEL");
    }

    @Test
    @DisplayName("Nested container markers are formatting rather than speech")
    void extract_whenProseUsesNestedContainers_omitsAllContainerMarkers() {
        String markdown = """
                - > Quoted prose.
                > - List prose.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result).isEqualTo("Quoted prose. List prose.");
    }

    @Test
    @DisplayName("Indented code is omitted without removing list continuation prose")
    void extract_whenMarkdownContainsIndentedCode_omitsCodeAndPreservesListProse() {
        String markdown = """
                Before.

                    INDENTED_CODE_SENTINEL
                    more code

                - List item

                    continuation prose

                      NESTED_CODE_SENTINEL
                    closing prose
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("Before.", "List item", "continuation prose", "closing prose", "After.")
                .doesNotContain("INDENTED_CODE_SENTINEL", "more code", "NESTED_CODE_SENTINEL");
    }

    @Test
    @DisplayName("Excess list-marker padding starts indented code")
    void extract_whenListMarkerPaddingExceedsFourColumns_omitsIndentedCode() {
        String markdown = """
                Before.
                -     UNORDERED_CODE_SENTINEL
                1.     ORDERED_CODE_SENTINEL
                > -     QUOTED_LIST_CODE_SENTINEL
                - Outer prose.
                  -     NESTED_LIST_CODE_SENTINEL
                  Preserved outer prose.
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("Before.", "Outer prose.", "Preserved outer prose.", "After.")
                .doesNotContain(
                        "UNORDERED_CODE_SENTINEL",
                        "ORDERED_CODE_SENTINEL",
                        "QUOTED_LIST_CODE_SENTINEL",
                        "NESTED_LIST_CODE_SENTINEL"
                );
    }

    @Test
    @DisplayName("Tabbed list markers use visual indentation for prose and code")
    void extract_whenListMarkerUsesTab_preservesProseAndOmitsIndentedCode() {
        String markdown = """
                -\tTabbed item

                      TAB_LIST_PROSE_SENTINEL

                        TAB_LIST_CODE_SENTINEL
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("Tabbed item", "TAB_LIST_PROSE_SENTINEL", "After.")
                .doesNotContain("TAB_LIST_CODE_SENTINEL");
    }

    @Test
    @DisplayName("Nested list continuation prose retains its enclosing list indentation")
    void extract_whenNestedListUsesLazyContinuation_preservesLaterContinuationProse() {
        String markdown = """
                - outer
                  - inner
                  lazy continuation

                    SPEAKABLE_CONTINUATION

                        NESTED_CODE_SENTINEL
                    closing continuation
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("outer", "inner", "lazy continuation", "SPEAKABLE_CONTINUATION", "closing continuation", "After.")
                .doesNotContain("NESTED_CODE_SENTINEL");
    }

    @Test
    @DisplayName("Dedenting from a nested list restores the outer code indentation")
    void extract_whenNestedListDedentsToOuterItem_omitsOuterIndentedCode() {
        String markdown = """
                - outer
                  - inner
                  outer prose

                      OUTER_LIST_CODE_SENTINEL
                  closing outer prose
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("outer", "inner", "outer prose", "closing outer prose", "After.")
                .doesNotContain("OUTER_LIST_CODE_SENTINEL");
    }

    @Test
    @DisplayName("A block interrupting a list restores top-level indented code handling")
    void extract_whenHeadingEndsList_omitsFollowingTopLevelIndentedCode() {
        String markdown = """
                - List item
                # Heading

                    TOP_LEVEL_CODE_SENTINEL
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("List item", "Heading", "After.")
                .doesNotContain("TOP_LEVEL_CODE_SENTINEL");
    }

    @Test
    @DisplayName("Setext headings and spaced thematic breaks allow following indented code")
    void extract_whenIndentedCodeFollowsSetextHeadingOrSpacedRule_omitsCodeWithoutBlankLines() {
        String markdown = """
                Setext heading
                =======
                    SETEXT_CODE_SENTINEL
                * * *
                    THEMATIC_BREAK_CODE_SENTINEL
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .isEqualTo("Setext heading After.")
                .doesNotContain("SETEXT_CODE_SENTINEL", "THEMATIC_BREAK_CODE_SENTINEL");
    }

    @Test
    @DisplayName("Indented code can begin immediately after standalone block syntax")
    void extract_whenIndentedCodeFollowsHeadingOrRule_omitsCodeWithoutRequiringBlankLine() {
        String markdown = """
                # Heading
                    HEADING_CODE_SENTINEL
                ---
                    RULE_CODE_SENTINEL
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("Heading", "After.")
                .doesNotContain("HEADING_CODE_SENTINEL", "RULE_CODE_SENTINEL");
    }

    @Test
    @DisplayName("Indented code inside a blockquote is omitted across quoted blank lines")
    void extract_whenIndentedCodeIsNestedInBlockquote_omitsCompleteBlock() {
        String markdown = """
                > Quoted prose.
                >
                >     BLOCKQUOTE_CODE_SENTINEL
                >
                >     MORE_BLOCKQUOTE_CODE_SENTINEL
                > Closing prose.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("Quoted prose.", "Closing prose.")
                .doesNotContain("BLOCKQUOTE_CODE_SENTINEL", "MORE_BLOCKQUOTE_CODE_SENTINEL");
    }

    @Test
    @DisplayName("Indented code inside a blockquote nested in a list is omitted")
    void extract_whenIndentedCodeUsesListAndBlockquoteContainers_omitsCompleteBlock() {
        String markdown = """
                - Outer list prose.
                  > Quoted prose.
                  >
                  >     NESTED_BLOCKQUOTE_CODE_SENTINEL
                  >
                  >     MORE_NESTED_BLOCKQUOTE_CODE_SENTINEL
                  > Closing quoted prose.
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("Outer list prose.", "Quoted prose.", "Closing quoted prose.", "After.")
                .doesNotContain("NESTED_BLOCKQUOTE_CODE_SENTINEL", "MORE_NESTED_BLOCKQUOTE_CODE_SENTINEL");
    }

    @Test
    @DisplayName("Multiline inline code protects math-like text")
    void extract_whenInlineCodeCrossesLineBreaks_preservesCompleteCodeSpan() {
        String markdown = """
                Before ``a
                $INLINE_CODE_MATH_SENTINEL = x$
                b`` after.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("Before", "a $INLINE_CODE_MATH_SENTINEL = x$ b", "after.")
                .doesNotContain("``");
    }

    @Test
    @DisplayName("Inline code delimiters do not cross excluded fenced blocks")
    void extract_whenBackticksSurroundAnExcludedFence_keepsLaterMathExcluded() {
        String markdown = """
                Before `unclosed
                ```java
                FENCED_CODE_SENTINEL
                ```
                Formula $AFTER_FENCE_MATH_SENTINEL = x$ `close.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("Before", "Formula", "close.")
                .doesNotContain("FENCED_CODE_SENTINEL", "AFTER_FENCE_MATH_SENTINEL");
    }

    @Test
    @DisplayName("Inline code delimiters do not cross excluded display math blocks")
    void extract_whenBackticksSurroundExcludedDisplayMath_keepsLaterMathExcluded() {
        String markdown = """
                Before `unclosed
                $$
                DISPLAY_MATH_BLOCK_SENTINEL = y
                $$
                Formula $AFTER_DISPLAY_MATH_SENTINEL = x$ `close.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("Before", "Formula", "close.")
                .doesNotContain("DISPLAY_MATH_BLOCK_SENTINEL", "AFTER_DISPLAY_MATH_SENTINEL");
    }

    @Test
    @DisplayName("Inline code delimiters do not cross table rows")
    void extract_whenBackticksAppearInSeparateTableRows_keepsMathExcluded() {
        String markdown = """
                | `unclosed |
                | --- |
                | $TABLE_ROW_MATH_SENTINEL = x$ `close |
                Done.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("Done.")
                .doesNotContain("TABLE_ROW_MATH_SENTINEL");
    }

    @Test
    @DisplayName("Inline code delimiters do not cross from a table into following prose")
    void extract_whenBacktickStartsInTableAndEndsInProse_keepsMathExcluded() {
        String markdown = """
                | `unclosed |
                Formula $AFTER_TABLE_MATH_SENTINEL = x$ `close.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("Formula", "close.")
                .doesNotContain("AFTER_TABLE_MATH_SENTINEL");
    }

    @Test
    @DisplayName("Inline code delimiters do not cross from a heading into following prose")
    void extract_whenBacktickStartsInHeadingAndEndsInProse_keepsMathExcluded() {
        String markdown = """
                # `unclosed
                Formula $AFTER_HEADING_MATH_SENTINEL = x$ `close.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("unclosed", "Formula", "close.")
                .doesNotContain("AFTER_HEADING_MATH_SENTINEL");
    }

    @Test
    @DisplayName("Inline code delimiters do not cross list-item boundaries")
    void extract_whenBackticksAppearInSeparateListItems_keepsMathExcluded() {
        String markdown = """
                - First `unclosed
                - Formula $SEPARATE_LIST_MATH_SENTINEL = x$ `close
                Done.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("First", "Formula", "Done.")
                .doesNotContain("SEPARATE_LIST_MATH_SENTINEL");
    }

    @Test
    @DisplayName("Recognized inline and display formulas are silently omitted")
    void extract_whenMarkdownContainsRecognizedMath_omitsFormulas() {
        String markdown = """
                Inline $INLINE_SENTINEL = x$ and \\(PAREN_SENTINEL = y\\) remain around prose.
                $$
                DISPLAY_SENTINEL = z
                $$
                \\[
                BRACKET_SENTINEL = q
                \\]
                \\frac{BARE_SENTINEL}{2} = r
                Done.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("Inline", "and", "remain around prose.", "Done.")
                .doesNotContain(
                        "INLINE_SENTINEL",
                        "PAREN_SENTINEL",
                        "DISPLAY_SENTINEL",
                        "BRACKET_SENTINEL",
                        "BARE_SENTINEL"
                );
    }

    @Test
    @DisplayName("Container display formulas and standalone LaTeX are silently omitted")
    void extract_whenMathBlocksAreNestedInContainers_omitsFormulasAndPreservesTrailingProse() {
        String markdown = """
                > Before quote.
                > $$
                > QUOTED_DOLLAR_MATH_SENTINEL = x
                > $$ After quote.
                - Before list.
                  \\[
                  LIST_BRACKET_MATH_SENTINEL = y
                  \\] After list.
                > \\frac{QUOTED_BARE_LATEX_SENTINEL}{2} = z
                - \\sum_{LIST_BARE_LATEX_SENTINEL}^{n} i = q
                Done.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("Before quote.", "After quote.", "Before list.", "After list.", "Done.")
                .doesNotContain(
                        "QUOTED_DOLLAR_MATH_SENTINEL",
                        "LIST_BRACKET_MATH_SENTINEL",
                        "QUOTED_BARE_LATEX_SENTINEL",
                        "LIST_BARE_LATEX_SENTINEL"
                );
    }

    @Test
    @DisplayName("Blockquote math indentation does not become a closing requirement")
    void extract_whenListBlockquoteMathHasOptionalIndent_acceptsShallowerValidCloser() {
        String markdown = """
                - List item.
                  >   $$
                  > BLOCKQUOTE_MATH_SENTINEL = x
                  > $$ Preserved quoted prose.
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("List item.", "Preserved quoted prose.", "After.")
                .doesNotContain("BLOCKQUOTE_MATH_SENTINEL");
    }

    @Test
    @DisplayName("A list nested later inside a blockquote requires its own math indentation")
    void extract_whenNestedListStateIsQuoteRelative_rejectsShallowerMathCloser() {
        String markdown = """
                - outer
                  > - inner
                  >   $$
                  >   NESTED_QUOTE_LIST_MATH_SENTINEL = x
                  > $$
                  > SHALLOW_MATH_LEAK_SENTINEL
                  >   $$ Preserved nested math prose.
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("outer", "inner", "Preserved nested math prose.", "After.")
                .doesNotContain("NESTED_QUOTE_LIST_MATH_SENTINEL", "SHALLOW_MATH_LEAK_SENTINEL");
    }

    @Test
    @DisplayName("Display math nested in lists and blockquotes preserves following prose")
    void extract_whenDisplayMathUsesMixedContainers_closesInMatchingContainerOrder() {
        String markdown = """
                - > $$
                  > LIST_QUOTE_MATH_SENTINEL = x
                  > $$ Preserved after list quote math.
                > - \\[
                >   QUOTE_LIST_MATH_SENTINEL = y
                >   \\] Preserved after quote list math.
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("Preserved after list quote math.", "Preserved after quote list math.", "After.")
                .doesNotContain("LIST_QUOTE_MATH_SENTINEL", "QUOTE_LIST_MATH_SENTINEL");
    }

    @Test
    @DisplayName("Leaving a display-math container preserves later prose")
    void extract_whenMathDelimiterAppearsInDifferentContainer_preservesProseOutsideFormula() {
        String markdown = """
                Before.
                $$
                TOP_LEVEL_MATH_SENTINEL = x
                > $$
                TOP_LEVEL_STILL_MATH_SENTINEL = y
                $$
                > \\[
                > QUOTED_MATH_SENTINEL = x
                \\]
                > QUOTED_STILL_MATH_SENTINEL = y
                > \\]
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result)
                .contains("Before.", "QUOTED_STILL_MATH_SENTINEL", "After.")
                .doesNotContain(
                        "TOP_LEVEL_MATH_SENTINEL",
                        "TOP_LEVEL_STILL_MATH_SENTINEL",
                        "QUOTED_MATH_SENTINEL"
                );
    }

    @Test
    @DisplayName("Escaped CommonMark punctuation remains speakable without backslashes")
    void extract_whenPunctuationIsEscaped_preservesLiteralPunctuation() {
        String result = ReadAloudTextExtractor.extract("Escaped \\<tag\\>, \\~tilde\\~, and \\& ampersand.");

        assertThat(result).isEqualTo("Escaped <tag>, ~tilde~, and & ampersand.");
    }

    @Test
    @DisplayName("Four-space backtick markers remain paragraph content without hiding following prose")
    void extract_whenBacktickMarkerCannotInterruptParagraph_preservesFollowingProse() {
        String markdown = """
                Before.
                    ```
                After.
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result).isEqualTo("Before. After.");
    }

    @Test
    @DisplayName("Messages made entirely from excluded content have no speech text")
    void extract_whenMarkdownContainsOnlyExcludedContent_returnsBlank() {
        String markdown = """
                ```mermaid
                graph TD
                ```
                $x = y$
                $$
                z = q
                $$
                """;

        String result = ReadAloudTextExtractor.extract(markdown);

        assertThat(result).isBlank();
    }

    @Test
    @DisplayName("Interrupted extraction fails closed")
    void extract_whenCurrentThreadIsInterrupted_returnsNoSpeechText() {
        Thread.currentThread().interrupt();
        try {
            assertThat(ReadAloudTextExtractor.extract("Speakable prose.")).isEmpty();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("Blank and null source have no speech text")
    void extract_whenMarkdownIsAbsent_returnsBlank() {
        assertThat(ReadAloudTextExtractor.extract(null)).isBlank();
        assertThat(ReadAloudTextExtractor.extract("  \n\t")).isBlank();
    }

    @Test
    @DisplayName("HTML-sensitive source remains readable text")
    void extract_whenMarkdownContainsHtmlSensitiveCharacters_preservesVisibleText() {
        String result = ReadAloudTextExtractor.extract("Use x < y && y > z, not <script>alert('x')</script>.");

        assertThat(result).isEqualTo("Use x < y && y > z, not <script>alert('x')</script>.");
    }
}
