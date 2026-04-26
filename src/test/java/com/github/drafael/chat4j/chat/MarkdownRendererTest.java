package com.github.drafael.chat4j.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownRendererTest {

    @Test
    @DisplayName("Null markdown renders an empty body")
    void toHtml_whenMarkdownIsNull_returnsEmptyBody() {
        String body = renderBody(null);

        assertThat(body).isEmpty();
    }

    @Test
    @DisplayName("Renderer always returns html shell with head style and body")
    void toHtml_whenCalled_wrapsContentInHtmlHeadStyleAndBody() {
        String html = MarkdownRenderer.toHtml("hello", false);

        assertThat(html)
                .startsWith("<html><head><style>")
                .contains("</style></head><body>")
                .contains("<p>hello</p>")
                .endsWith("</body></html>");
    }

    @Test
    @DisplayName("Heading syntax from one to six hashes renders heading tags")
    void toHtml_whenMarkdownContainsHeadings_rendersHeadingLevelsOneToSix() {
        String body = renderBody("# One\n## Two\n### Three\n#### Four\n##### Five\n###### Six\n####### Seven");

        assertThat(body)
                .contains("<h1>One</h1>")
                .contains("<h2>Two</h2>")
                .contains("<h3>Three</h3>")
                .contains("<h4>Four</h4>")
                .contains("<h5>Five</h5>")
                .contains("<h6>Six</h6>")
                .contains("<p>####### Seven</p>");
    }

    @Test
    @DisplayName("List syntax renders unordered and ordered lists")
    void toHtml_whenMarkdownContainsLists_rendersUnorderedAndOrderedLists() {
        String body = renderBody("- Apple\n- Banana\n\n1. First\n2. Second\nDone");

        assertThat(body)
                .contains("<ul>")
                .contains("<li>Apple</li>")
                .contains("<li>Banana</li>")
                .contains("</ul>")
                .contains("<ol>")
                .contains("<li>First</li>")
                .contains("<li>Second</li>")
                .contains("</ol>")
                .contains("<p>Done</p>");
    }

    @Test
    @DisplayName("Leading indentation before list markers still renders list items")
    void toHtml_whenListMarkersAreIndented_rendersListItems() {
        String body = renderBody(" * **Role:** value\n * `target`: value\n * plain item");

        assertThat(body)
                .contains("<ul>")
                .contains("<li><b>Role:</b> value</li>")
                .contains("<li><code style=\"background-color:")
                .contains("<li>plain item</li>")
                .contains("</ul>");
    }

    @Test
    @DisplayName("Horizontal rule markers render hr tags")
    void toHtml_whenMarkdownContainsHorizontalRules_rendersHorizontalRuleTags() {
        String body = renderBody("---\n***\n___");

        assertThat(body).contains("<hr><hr><hr>");
    }

    @Test
    @DisplayName("Fenced code blocks render escaped code with compact optional language header")
    void toHtml_whenMarkdownContainsFencedCode_rendersCodeBlockWithCompactHeader() {
        String body = renderBody("```java\nint x = 1;\n```\n```\n<tag>\n```");

        assertThat(body)
                .contains("java</font>")
                .contains("int x = 1;")
                .contains("&lt;tag&gt;")
                .contains("padding: 2px 8px")
                .contains("<pre style=\"margin: 0;\">")
                .doesNotContain("copy-code:")
                .doesNotContain("code-copy-link");
    }

    @Test
    @DisplayName("Pipe table syntax renders table headers and rows")
    void toHtml_whenMarkdownContainsTable_rendersTableRowsAndCells() {
        String body = renderBody("| Name | Age |\n| --- | --- |\n| Ana | 30 |");

        assertThat(body)
                .contains("<table class=\"md-table\" width=\"100%\"")
                .contains("<b>Name</b>")
                .contains("<b>Age</b>")
                .contains(">Ana</td>")
                .contains(">30</td>");
    }

    @Test
    @DisplayName("Inline markdown renders bold italic and inline code")
    void toHtml_whenMarkdownContainsInlineFormatting_rendersBoldItalicAndInlineCode() {
        String body = renderBody("**bold** __b2__ *italic* _i2_ `code`");

        assertThat(body)
                .contains("<b>bold</b>")
                .contains("<b>b2</b>")
                .contains("<i>italic</i>")
                .contains("<i>i2</i>")
                .contains("<code style=\"background-color:")
                .contains(">code</font></code>");
    }

    @Test
    @DisplayName("Inline LaTeX fallback renders math expression as inline code")
    void toHtml_whenMarkdownContainsInlineLatex_rendersInlineCodeFallback() {
        String body = renderBody("Faraday law uses $\\mathcal{E}$ as electromotive force.");

        assertThat(body)
                .contains("<code class=\"md-latex-inline\"")
                .contains("$\\mathcal{E}$")
                .doesNotContain("<i>\\mathcal{E}</i>");
    }

    @Test
    @DisplayName("Display LaTeX fallback renders math block as highlighted code block")
    void toHtml_whenMarkdownContainsDisplayLatexBlock_rendersCodeBlockFallback() {
        String body = renderBody("$$\n\\mathcal{E} = - \\frac{d\\Phi_B}{dt}\n$$");

        assertThat(body)
                .contains("class=\"md-code-block md-latex-block\"")
                .contains("border: 1px dashed")
                .contains("latex</font>")
                .contains("<pre style=\"margin: 0;\">")
                .contains("\\mathcal{E} = - \\frac{d\\Phi_B}{dt}");
    }

    @Test
    @DisplayName("Blank lines produce line breaks between paragraphs")
    void toHtml_whenMarkdownContainsBlankLines_rendersLineBreakBetweenParagraphs() {
        String body = renderBody("first\n\nsecond");

        assertThat(body).contains("<p>first</p><br><p>second</p>");
    }

    @Test
    @DisplayName("HTML content is escaped in normal paragraphs")
    void toHtml_whenMarkdownContainsHtmlCharacters_escapesHtmlCharacters() {
        String body = renderBody("<div> & \"quoted\"");

        assertThat(body).contains("<p>&lt;div&gt; &amp; &quot;quoted&quot;</p>");
    }

    @Test
    @DisplayName("Autolinks and markdown links render anchor tags")
    void toHtml_whenMarkdownContainsLinks_rendersAnchors() {
        String body = renderBody("<https://example.com/path-one> and [OpenRouter](https://openrouter.ai/docs?x=1&y=2)");

        assertThat(body)
                .contains("href=\"https://example.com/path-one\"")
                .contains(">https://example.com/path-one</a>")
                .contains("href=\"https://openrouter.ai/docs?x=1&amp;y=2\"")
                .contains(">OpenRouter</a>")
                .doesNotContain("&#8203;");
    }

    @Test
    @DisplayName("Inline br tags are preserved as line breaks")
    void toHtml_whenMarkdownContainsInlineBreakTag_rendersHtmlBreak() {
        String body = renderBody("Line one<br>Line two");

        assertThat(body).contains("<p>Line one<br>Line two</p>");
    }

    @Test
    @DisplayName("Malformed bold fenced markers inside table cell are normalized")
    void toHtml_whenTableCellContainsMalformedFenceMarkers_normalizesInlineCodeRendering() {
        String body = renderBody("| C |\n| --- |\n| **```typescript** <br> **`const x = 1;`** <br> `return x;` ** |");

        assertThat(body)
                .doesNotContain("```")
                .doesNotContain("**")
                .contains("typescript")
                .contains("const x = 1;")
                .contains("return x;");
    }

    @Test
    @DisplayName("Hash without trailing space does not produce heading")
    void toHtml_whenHeadingMarkerHasNoSpace_rendersPlainParagraph() {
        String body = renderBody("#No heading");

        assertThat(body).contains("<p>#No heading</p>");
    }

    @Test
    @DisplayName("Changing list type closes unordered list before ordered list")
    void toHtml_whenListTypeChanges_closesUnorderedListBeforeOrderedList() {
        String body = renderBody("- one\n1. two");

        assertThat(body).contains("</ul><ol>");
    }

    @Test
    @DisplayName("Ordered list preserves explicit numbering when new list block starts")
    void toHtml_whenOrderedListRestarts_preservesStartNumber() {
        String body = renderBody("1. first\n\n2. second\n\n3. third");

        assertThat(body)
                .contains("<ol><li>first</li></ol>")
                .contains("<ol start=\"2\"><li>second</li></ol>")
                .contains("<ol start=\"3\"><li>third</li></ol>");
    }

    @Test
    @DisplayName("Blockquote syntax renders quote block and inline markdown")
    void toHtml_whenMarkdownContainsBlockquote_rendersBlockquoteWithInlineFormatting() {
        String body = renderBody("> **\"Hey, this function doesn't return the value directly.\"**");

        assertThat(body)
                .contains("<blockquote>")
                .contains("<b>&quot;Hey, this function doesn't return the value directly.&quot;</b>")
                .contains("</blockquote>");
    }

    @Test
    @DisplayName("Unclosed fenced code block still renders collected code")
    void toHtml_whenCodeFenceIsUnclosed_rendersCodeBlockAtEnd() {
        String body = renderBody("```java\nint value = 10;");

        assertThat(body)
                .contains("java</font>")
                .contains("int value = 10;")
                .contains("<pre style=\"margin: 0;\">");
    }

    @Test
    @DisplayName("Table block ends when non-table line appears")
    void toHtml_whenTableIsFollowedByParagraph_rendersTableThenParagraph() {
        String body = renderBody("| Col |\n| --- |\n| v |\nafter");

        assertThat(body).contains("</table><p>after</p>");
    }

    private static String renderBody(String markdown) {
        String html = MarkdownRenderer.toHtml(markdown, false);
        int bodyStart = html.indexOf("<body>");
        int bodyEnd = html.indexOf("</body>");
        return html.substring(bodyStart + 6, bodyEnd);
    }
}
