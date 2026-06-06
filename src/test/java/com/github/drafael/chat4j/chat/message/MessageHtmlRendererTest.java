package com.github.drafael.chat4j.chat.message;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.intellijthemes.FlatArcIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatDraculaIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatNordIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatSolarizedDarkIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatSolarizedLightIJTheme;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMTGitHubDarkIJTheme;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMTGitHubIJTheme;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMTMaterialLighterIJTheme;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.github.drafael.chat4j.chat.RenderMode;
import com.github.drafael.chat4j.provider.api.Role;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import java.awt.Font;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MessageHtmlRendererTest {

    private final MessageHtmlRenderer subject = new MessageHtmlRenderer();

    static Stream<Arguments> flatLafThemes() {
        return Stream.of(
                Arguments.of("FlatLaf Light", FlatLightLaf.class),
                Arguments.of("FlatLaf Dark", FlatDarkLaf.class),
                Arguments.of("FlatLaf IntelliJ", FlatIntelliJLaf.class),
                Arguments.of("FlatLaf Darcula", FlatDarculaLaf.class),
                Arguments.of("FlatLaf macOS Light", FlatMacLightLaf.class),
                Arguments.of("FlatLaf macOS Dark", FlatMacDarkLaf.class),
                Arguments.of("Arc IntelliJ Theme", FlatArcIJTheme.class),
                Arguments.of("Dracula IntelliJ Theme", FlatDraculaIJTheme.class),
                Arguments.of("Nord IntelliJ Theme", FlatNordIJTheme.class),
                Arguments.of("GitHub Material Theme", FlatMTGitHubIJTheme.class),
                Arguments.of("GitHub Dark Material Theme", FlatMTGitHubDarkIJTheme.class),
                Arguments.of("Material Lighter Theme", FlatMTMaterialLighterIJTheme.class),
                Arguments.of("Solarized Light IntelliJ Theme", FlatSolarizedLightIJTheme.class),
                Arguments.of("Solarized Dark IntelliJ Theme", FlatSolarizedDarkIJTheme.class)
        );
    }

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
    @DisplayName("Assistant preview mode delegates to markdown rendering")
    void render_whenAssistantPreviewModeSelected_rendersMarkdownHtml() {
        String html = subject.render(Role.ASSISTANT, RenderMode.PREVIEW, "**bold**", false);

        assertThat(html)
                .contains("<p><b>bold</b></p>")
                .startsWith("<html><head><style>")
                .endsWith("</body></html>");
    }

    @Test
    @DisplayName("Code blocks do not inherit the text area font when no code font is configured")
    void render_whenMonospacedFontMissing_usesMonospaceFallbackStack() {
        Object originalMonospacedFont = UIManager.get("monospaced.font");
        Object originalTextAreaFont = UIManager.get("TextArea.font");
        try {
            UIManager.put("monospaced.font", null);
            UIManager.put("TextArea.font", new Font("Dialog", Font.PLAIN, 13));

            String html = renderCodeBlock();

            assertThat(html)
                    .contains("class=\"md-code-block\"")
                    .contains("face=\"Monaco, Menlo, Consolas, 'Courier New', monospace\"")
                    .doesNotContain("face=\"Dialog\"");
        } finally {
            UIManager.put("monospaced.font", originalMonospacedFont);
            UIManager.put("TextArea.font", originalTextAreaFont);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("flatLafThemes")
    @DisplayName("Code blocks keep a monospace font across FlatLaf themes")
    void render_whenFlatLafThemeActive_keepsCodeBlocksMonospaced(
            String themeName,
            Class<? extends LookAndFeel> lookAndFeelClass
    ) throws Exception {
        LookAndFeel originalLookAndFeel = UIManager.getLookAndFeel();
        try {
            LookAndFeel lookAndFeel = lookAndFeelClass.getDeclaredConstructor().newInstance();
            UIManager.setLookAndFeel(lookAndFeel);
            Font defaultFont = UIManager.getFont("defaultFont");
            Font monospacedFont = UIManager.getFont("monospaced.font");

            String html = renderCodeBlock();

            assertThat(html)
                    .as(themeName)
                    .contains("class=\"md-code-block\"")
                    .contains("System.out.println");
            assertThat(monospacedFont)
                    .as("%s should expose a monospaced.font UI default", themeName)
                    .isNotNull();
            assertThat(html)
                    .as("%s should use the configured code font", themeName)
                    .contains("face=\"%s\"".formatted(monospacedFont.getFamily()));
            assertThat(monospacedFont.getFamily())
                    .as("%s code font should differ from the proportional default font", themeName)
                    .isNotEqualTo(defaultFont.getFamily());
        } finally {
            UIManager.setLookAndFeel(originalLookAndFeel);
        }
    }

    private String renderCodeBlock() {
        return subject.render(Role.ASSISTANT, RenderMode.PREVIEW, "```java\nSystem.out.println(\"hello\");\n```", false);
    }
}
