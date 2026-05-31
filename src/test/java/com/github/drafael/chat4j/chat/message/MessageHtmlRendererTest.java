package com.github.drafael.chat4j.chat.message;

import com.github.drafael.chat4j.chat.RenderMode;
import com.github.drafael.chat4j.provider.api.Role;
import org.jsoup.Jsoup;
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
                .contains(">FALLBACK</span>Files use text references")
                .contains("Build me a plan")
                .doesNotContain("[SKILL]")
                .doesNotContain("[FALLBACK]");
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
    @DisplayName("Assistant preview mode delegates to markdown rendering")
    void render_whenAssistantPreviewModeSelected_rendersMarkdownHtml() {
        String html = subject.render(Role.ASSISTANT, RenderMode.PREVIEW, "**bold**", false);

        assertThat(html)
                .contains("<p><b>bold</b></p>")
                .startsWith("<html><head><style>")
                .endsWith("</body></html>");
    }
}
