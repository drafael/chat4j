package com.github.drafael.chat4j.chat.render;

import org.jsoup.Jsoup;
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
                .contains("data-code-language=\"java\"")
                .contains("java</font>")
                .contains("int x = 1;")
                .contains("&lt;tag&gt;")
                .contains("padding: 2px 8px")
                .contains("<pre style=\"margin: 0;\">")
                .doesNotContain("copy-code:")
                .doesNotContain("code-copy-link");
    }

    @Test
    @DisplayName("Mermaid fenced blocks render with diagram marker classes")
    void toHtml_whenMarkdownContainsMermaidFence_marksDiagramBlock() {
        String body = renderBody("```mermaid\nflowchart TD\n  A --> B\n```");

        assertThat(body)
                .contains("class=\"md-code-block md-diagram-block md-mermaid-block\"")
                .contains("data-code-language=\"mermaid\"")
                .contains("flowchart TD")
                .contains("A --&gt; B");
    }

    @Test
    @DisplayName("SMILES fenced blocks render with chemistry marker classes")
    void toHtml_whenMarkdownContainsSmilesFence_marksChemistryDiagramBlock() {
        String body = renderBody("```smiles\nCn1cnc2c1c(=O)n(C)c(=O)n2C\n```");

        assertThat(body)
                .contains("class=\"md-code-block md-diagram-block md-chem-block md-smiles-block\"")
                .contains("data-code-language=\"smiles\"")
                .contains("Cn1cnc2c1c(=O)n(C)c(=O)n2C");
    }

    @Test
    @DisplayName("MOL fenced blocks render with chemistry marker classes")
    void toHtml_whenMarkdownContainsMolFence_marksChemistryDiagramBlock() {
        String body = renderBody("```mol\nEthanol\n  Chat4J\n\n  3  2  0  0  0  0            999 V2000\n    0.0 0.0 0.0 C 0 0 0 0 0 0 0 0 0 0 0 0\n    1.0 0.0 0.0 C 0 0 0 0 0 0 0 0 0 0 0 0\n    2.0 0.0 0.0 O 0 0 0 0 0 0 0 0 0 0 0 0\n  1  2  1  0\n  2  3  1  0\nM  END\n```");

        assertThat(body)
                .contains("class=\"md-code-block md-diagram-block md-chem-block md-mol-block\"")
                .contains("data-code-language=\"mol\"")
                .contains("Ethanol")
                .contains("V2000");
    }

    @Test
    @DisplayName("SDF fenced blocks render with chemistry marker classes")
    void toHtml_whenMarkdownContainsSdfFence_marksChemistryDiagramBlock() {
        String body = renderBody("```sdf\nBenzene\n  Chat4J\n\n  6  6  0  0  0  0            999 V2000\nM  END\n$$$$\n```");

        assertThat(body)
                .contains("class=\"md-code-block md-diagram-block md-chem-block md-sdf-block\"")
                .contains("data-code-language=\"sdf\"")
                .contains("Benzene")
                .contains("$$$$");
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
    @DisplayName("Currency ranges do not render as inline LaTeX")
    void toHtml_whenMarkdownContainsCurrencyRange_keepsCurrencyTextReadable() {
        String body = renderBody("Market value grew from $24.7 billion in 2025 to $45.8 billion by 2035.");

        assertThat(body)
                .contains("$24.7 billion in 2025 to $45.8 billion")
                .doesNotContain("chat4j-math-inline")
                .doesNotContain("md-latex-inline");
    }

    @Test
    @DisplayName("Currency phrases with plus signs do not render as inline LaTeX")
    void toHtml_whenCurrencyTextContainsPlusSign_keepsCurrencyTextReadable() {
        String body = renderBody("It costs $5 + tax today and $7 tomorrow.");

        assertThat(body)
                .contains("It costs $5 + tax today and $7 tomorrow.")
                .doesNotContain("chat4j-math-inline")
                .doesNotContain("md-latex-inline");
    }

    @Test
    @DisplayName("Repeated-dollar currency ranges do not render as inline LaTeX")
    void toHtml_whenCurrencyRangeRepeatsDollarSign_keepsCurrencyTextReadable() {
        String body = renderBody("It costs $5 - $7 today, or $5-$7 without spaces.");

        assertThat(body)
                .contains("It costs $5 - $7 today, or $5-$7 without spaces.")
                .doesNotContain("chat4j-math-inline")
                .doesNotContain("md-latex-inline");
    }

    @Test
    @DisplayName("Numeric LaTeX units and ranges render as inline math fallbacks")
    void toHtml_whenMarkdownContainsNumericLatexUnitsAndRanges_rendersInlineCodeFallbacks() {
        String body = renderBody("Use $10\\text{g}$ of salt, (about $1-2\\text{g}$), for about $10-15$ minutes.");
        var document = Jsoup.parseBodyFragment(body);

        assertThat(document.select("code.md-latex-inline")).hasSize(3);
        assertThat(body)
                .contains("$10\\text{g}$")
                .contains("$1-2\\text{g}$")
                .contains("$10-15$");
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
    @DisplayName("Single-line display LaTeX fallback renders math block as highlighted code block")
    void toHtml_whenMarkdownContainsSingleLineDisplayLatexBlock_rendersCodeBlockFallback() {
        String body = renderBody("$$\\oiint_C \\mathbf{E} \\cdot d\\boldsymbol{\\ell} = -\\frac{d\\Phi_B}{dt}$$");

        assertThat(body)
                .contains("class=\"md-code-block md-latex-block\"")
                .contains("latex</font>")
                .contains("\\oiint_C \\mathbf{E} \\cdot d\\boldsymbol{\\ell} = -\\frac{d\\Phi_B}{dt}")
                .doesNotContain("$$");
    }

    @Test
    @DisplayName("Bracket display LaTeX fallback renders math block as highlighted code block")
    void toHtml_whenMarkdownContainsBracketDisplayLatexBlock_rendersCodeBlockFallback() {
        String body = renderBody("\\[\nm = \\frac{Q \\cdot M}{F \\cdot z}\n\\]");

        assertThat(body)
                .contains("class=\"md-code-block md-latex-block\"")
                .contains("latex</font>")
                .contains("m = \\frac{Q \\cdot M}{F \\cdot z}")
                .doesNotContain("\\[")
                .doesNotContain("\\]");
    }

    @Test
    @DisplayName("Bare display LaTeX lines render as math fallback blocks")
    void toHtml_whenMarkdownContainsBareDisplayLatexLine_rendersCodeBlockFallback() {
        String formula = "\\text{PI}_3^- (\\text{aq}) + \\text{P} (\\text{s}) \\xrightarrow{\\text{Heat}} \\text{I}_3^- \\text{(in situ)}} \\text{I}_5^- + \\text{H}_2\\text{O} + \\text{PH}_3(\\text{g})";

        String body = renderBody(formula);

        assertThat(body)
                .contains("class=\"md-code-block md-latex-block\"")
                .contains("latex</font>")
                .contains("\\xrightarrow{\\text{Heat}}")
                .doesNotContain("<p>" + formula + "</p>");
    }

    @Test
    @DisplayName("Parenthesized inline LaTeX fallback renders math expression as inline code")
    void toHtml_whenMarkdownContainsParenthesizedInlineLatex_rendersInlineCodeFallback() {
        String body = renderBody("Total charge \\(Q\\) equals \\(I \\cdot t\\).");

        assertThat(body)
                .contains("<code class=\"md-latex-inline\"")
                .contains("\\(Q\\)")
                .contains("\\(I \\cdot t\\)");
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
        String body = renderBody("<https://example.com/path-one> and [OpenRouter](<https://openrouter.ai/docs/a_(b)?x=1&y=2>)");

        assertThat(body)
                .contains("href=\"https://example.com/path-one\"")
                .contains(">https://example.com/path-one</a>")
                .contains("href=\"https://openrouter.ai/docs/a_(b)?x=1&amp;y=2\"")
                .contains(">OpenRouter</a>")
                .doesNotContain("&#8203;");
    }

    @Test
    @DisplayName("Markdown links allow escaped brackets in labels")
    void toHtml_whenMarkdownLinkLabelContainsEscapedBrackets_rendersAnchor() {
        String body = renderBody("[About \\[PDF\\]](<https://example.com/report>)");

        assertThat(body)
                .contains("href=\"https://example.com/report\"")
                .contains(">About [PDF]</a>");
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

    @Test
    @DisplayName("Plain source references link to matching source list URLs")
    void toHtml_whenMarkdownContainsNumberedSourceList_linksInlineReferences() {
        String body = renderBody("""
                Feature A[1][2]

                ---
                [1] https://example.com/one
                [2] https://example.com/two
                """);

        assertThat(body)
                .contains("href=\"https://example.com/one\">[1]</a>")
                .contains("href=\"https://example.com/two\">[2]</a>")
                .contains("href=\"https://example.com/one\">https://example.com/one</a>")
                .contains("href=\"https://example.com/two\">https://example.com/two</a>");
    }

    private static String renderBody(String markdown) {
        String html = MarkdownRenderer.toHtml(markdown, false);
        int bodyStart = html.indexOf("<body>");
        int bodyEnd = html.indexOf("</body>");
        return html.substring(bodyStart + 6, bodyEnd);
    }
}
