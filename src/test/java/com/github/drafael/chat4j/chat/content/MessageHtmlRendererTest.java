package com.github.drafael.chat4j.chat.content;

import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import org.jsoup.Jsoup;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageHtmlRendererTest {

    private final MessageHtmlRenderer subject = new MessageHtmlRenderer();

    @Test
    @DisplayName("User messages render skill and fallback badges")
    void render_whenUserMessageContainsMarkers_rendersBadgeHtml() {
        String html = subject.render(
                Role.USER,
                RenderMode.PREVIEW,
                "[SKILL] brainstorm\n[FALLBACK] Files use text references\nBuild me a plan",
                false
        );

        assertThat(html)
                .contains("badge skill")
                .contains("badge fallback")
                .contains(">SKILL</span>brainstorm")
                .contains(">ATTACHMENT</span>")
                .contains("Files use text references")
                .contains("Build me a plan")
                .doesNotContain("[SKILL]")
                .doesNotContain("[FALLBACK]");
    }

    @Test
    @DisplayName("Verbose file fallback notices render as compact attachment notices")
    void render_whenUserMessageContainsVerboseFallbackNotice_compactsNotice() {
        String html = subject.render(
                Role.USER,
                RenderMode.PREVIEW,
                "[FALLBACK] Anthropic (claude-sonnet-4-6) supports rich input, but file upload mapping is not enabled yet in Chat4J; sending extracted text when available, otherwise file references.\ndescribe and summarize",
                false
        );

        assertThat(html)
                .contains(">ATTACHMENT</span>")
                .contains("Extracted text sent · native upload not mapped")
                .contains("describe and summarize");
    }

    @Test
    @DisplayName("User preview mode delegates to markdown rendering")
    void render_whenUserPreviewModeSelected_rendersMarkdownHtml() {
        String html = subject.render(Role.USER, RenderMode.PREVIEW, "**bold**\n\n```java\nString x;\n```", false);

        assertThat(html)
                .contains("<p><b>bold</b></p>")
                .contains("class=\"md-code-block\"")
                .contains("String x;")
                .startsWith("<html><head><style>")
                .endsWith("</body></html>");
    }

    @Test
    @DisplayName("Markdown tables keep inline-code pipes inside the same cell")
    void render_whenTableCellsContainInlineCodePipes_keepsColumnCountStable() {
        String html = subject.render(Role.ASSISTANT, RenderMode.PREVIEW, """
                | Area | Java | TypeScript | Why It Matters |
                | --- | --- | --- | --- |
                | Null Handling | `Optional<T>` | `T | null | undefined` — but no built-in null-safety | Enable `--strictNullChecks`. |
                """, false);

        var document = Jsoup.parse(html);
        var dataRowCells = document.select("table.md-table tr").get(1).select("td");

        assertThat(dataRowCells).hasSize(4);
        assertThat(dataRowCells.get(2).text()).contains("T | null | undefined");
        assertThat(dataRowCells.get(3).text()).contains("--strictNullChecks");
    }

    @Test
    @DisplayName("Preview mode preserves chemistry formulas as explicit inline LaTeX fallback")
    void render_whenChemistryFormulaIsInline_preservesLatexFallbackClass() {
        String html = subject.render(Role.ASSISTANT, RenderMode.PREVIEW, "Carbon dioxide is $\\ce{CO2}$.", false);

        var document = Jsoup.parse(html);
        var inlineMath = document.select("code.md-latex-inline");

        assertThat(inlineMath).hasSize(1);
        assertThat(inlineMath.text()).contains("$\\ce{CO2}$");
    }

    @Test
    @DisplayName("Preview mode keeps formulas in table cells detectable for WebView math rendering")
    void render_whenTableCellContainsFormula_preservesLatexFallbackClass() {
        String html = subject.render(Role.ASSISTANT, RenderMode.PREVIEW, """
                | Species | Formula |
                | --- | --- |
                | Carbon dioxide | $\\ce{CO2}$ |
                """, false);

        var document = Jsoup.parse(html);
        var formulaCell = document.select("table.md-table tr").get(1).select("td").get(1);

        assertThat(formulaCell.select("code.md-latex-inline")).hasSize(1);
        assertThat(formulaCell.text()).contains("$\\ce{CO2}$");
    }

    @Test
    @DisplayName("Preview mode keeps model math with nested text dollars as one formula")
    void render_whenInlineMathContainsNestedTextDollars_preservesSingleLatexFallback() {
        String html = subject.render(
                Role.ASSISTANT,
                RenderMode.PREVIEW,
                "A hexagon with a circle drawn inside it ($\\text{Drawings often use $\\pi$ notation}$).",
                false
        );

        var document = Jsoup.parse(html);
        var inlineMath = document.select("code.md-latex-inline");

        assertThat(inlineMath).hasSize(1);
        assertThat(inlineMath.text()).isEqualTo("$\\text{Drawings often use $\\pi$ notation}$");
        assertThat(document.text()).contains("A hexagon with a circle drawn inside it", ").");
    }

    @Test
    @DisplayName("Preview mode keeps display arrays in table cells detectable for math rendering")
    void render_whenTableCellContainsDisplayArray_preservesLatexFallbackClass() {
        String html = subject.render(Role.ASSISTANT, RenderMode.PREVIEW, """
                | Example | Visual |
                | --- | --- |
                | Methane | $$\\begin{array}{c} \\text{H} & \\text{H} \\\\ \\text{H} & \\text{C} & \\text{H} \\end{array}$$ |
                """, false);

        var document = Jsoup.parse(html);
        var formulaCell = document.select("table.md-table tr").get(1).select("td").get(1);

        assertThat(formulaCell.select("code.md-latex-inline")).hasSize(1);
        assertThat(formulaCell.text()).contains("$$\\begin{array}");
    }

    @Test
    @DisplayName("Preview mode does not split table cells on pipes inside LaTeX formulas")
    void render_whenTableCellFormulaContainsVerticalBondPipes_keepsTableColumnsStable() {
        String html = subject.render(Role.ASSISTANT, RenderMode.PREVIEW, """
                | Convention | What it shows | Example Molecule | Visual Representation | Best Used For... |
                | :--- | :--- | :--- | :--- | :--- |
                | **Lewis Structure** | Shows *every* atom and *every* bond. | $\\text{CH}_4$ | $$\\begin{array}{c} \\text{H} & \\text{H} \\\\ | & | \\\\ \\text{H} - \\text{C} - \\text{H} \\\\ | & | \\\\ \\text{H} & \\text{H} \\end{array}$$ | Teaching fundamentals; showing lone pairs. |
                """, false);

        var document = Jsoup.parse(html);
        var dataRowCells = document.select("table.md-table tr").get(1).select("td");
        var visualCell = dataRowCells.get(3);

        assertThat(dataRowCells).hasSize(5);
        assertThat(visualCell.select("code.md-latex-inline")).hasSize(1);
        assertThat(visualCell.text()).contains("$$\\begin{array}", "\\text{C}");
        assertThat(dataRowCells.get(4).text()).contains("Teaching fundamentals");
    }

    @Test
    @DisplayName("Markdown mode renders escaped source text with original formatting")
    void render_whenMarkdownModeSelected_escapesTextAndPreservesFormatting() {
        String markdown = "<b>x</b>\n  **second**\n```java\nString x;\n```";
        String userHtml = subject.render(Role.USER, RenderMode.MARKDOWN, markdown, false);
        String assistantHtml = subject.render(Role.ASSISTANT, RenderMode.MARKDOWN, markdown, false);

        assertThat(userHtml)
                .contains("<pre>&lt;b&gt;x&lt;/b&gt;\n  **second**\n```java\nString x;\n```</pre>")
                .doesNotContain("<p>")
                .doesNotContain("<b>x</b>");
        assertThat(assistantHtml)
                .contains("<pre>&lt;b&gt;x&lt;/b&gt;\n  **second**\n```java\nString x;\n```</pre>")
                .doesNotContain("<p>")
                .doesNotContain("<b>x</b>");
    }

    @Test
    @DisplayName("Assistant preview HTML preserves square brackets for JEditorPane source citations")
    void render_whenAssistantHasNumberedSources_keepsBracketedCitationText() {
        String html = subject.render(Role.ASSISTANT, RenderMode.PREVIEW, """
                Claim[1]

                Sources:
                [1] https://example.com/source
                """, false);

        assertThat(html).contains("href=\"https://example.com/source\">[1]</a>");
    }

    @Test
    @DisplayName("Assistant preview mode renders generated image parts inline")
    void render_whenAssistantHasGeneratedImagePart_rendersOpenableImage() {
        var ref = new AttachmentRef(UUID.randomUUID(), "/tmp/generated.png", "generated.png", "image/png", 12L, "hash");
        String html = subject.render(
                Role.ASSISTANT,
                RenderMode.PREVIEW,
                List.of(new TextPart("Done"), new GeneratedImagePart(ref, 32, 24, "Generated cat")),
                false
        );

        var document = Jsoup.parse(html);
        var link = document.selectFirst("a.generated-image-wrap");

        assertThat(link).isNotNull();
        assertThat(link.attr("data-action")).isEqualTo("open-attachment");
        assertThat(link.attr("data-attachment-path")).isEqualTo("/tmp/generated.png");
        assertThat(link.attr("style")).contains("border: none", "text-decoration: none");
        assertThat(link.selectFirst("img.generated-image").attr("alt")).isEqualTo("Generated cat");
        assertThat(link.selectFirst("img.generated-image").attr("border")).isEqualTo("0");
        assertThat(link.selectFirst("img.generated-image").attr("width")).isEqualTo("32");
        assertThat(link.selectFirst("img.generated-image").attr("height")).isEqualTo("24");
    }

    @Test
    @DisplayName("Assistant preview mode delegates to markdown rendering")
    void render_whenAssistantPreviewModeSelected_rendersMarkdownHtml() {
        String html = subject.render(Role.ASSISTANT, RenderMode.PREVIEW, "**bold**", false);

        assertThat(html)
                .contains("<p><b>bold</b></p>")
                .startsWith("<html><head><style>")
                .endsWith("</body></html>");
    }
}
