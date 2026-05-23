package com.github.drafael.chat4j.chat.message;

import com.github.drafael.chat4j.chat.RenderMode;
import com.github.drafael.chat4j.provider.api.Role;
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
