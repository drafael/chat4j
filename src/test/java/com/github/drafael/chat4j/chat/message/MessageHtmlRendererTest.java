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
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.github.drafael.chat4j.chat.RenderMode;
import com.github.drafael.chat4j.provider.api.Role;
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
    void render_whenFlatLafThemeActive_keepsCodeBlocksMonospaced(String themeName, Class<? extends LookAndFeel> lookAndFeelClass) throws Exception {
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
