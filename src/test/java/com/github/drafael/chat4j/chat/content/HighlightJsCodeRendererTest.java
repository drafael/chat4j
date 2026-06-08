package com.github.drafael.chat4j.chat.content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HighlightJsCodeRendererTest {

    private final HighlightJsCodeRenderer subject = HighlightJsCodeRenderer.instance();

    @Test
    @DisplayName("Java code renders Highlight.js token spans")
    void render_whenJavaLanguageProvided_returnsHighlightedHtml() {
        var result = subject.render("class Demo { String value = \"ok\"; }", "java");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow())
                .contains("hljs-keyword")
                .contains("hljs-string");
    }

    @Test
    @DisplayName("Java primitive type spans are separated from class type spans")
    void render_whenJavaContainsPrimitiveAndClassTypes_marksOnlyPrimitiveAsKeyword() {
        var result = subject.render("int count = 0; Thread thread = new Thread();", "java");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow())
                .contains("class=\"hljs-keyword chat4j-primitive\">int</span>")
                .contains("class=\"hljs-type\">Thread</span>")
                .contains("class=\"hljs-title class_\">Thread</span>");
    }

    @Test
    @DisplayName("Markdown source renders Markdown token spans")
    void render_whenMarkdownLanguageProvided_returnsHighlightedHtml() {
        var result = subject.render("# Title\n\n```java\nclass Demo {}\n```", "markdown");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow())
                .contains("hljs-section")
                .contains("hljs-code");
    }

    @Test
    @DisplayName("Missing language does not trigger auto detection")
    void render_whenLanguageMissing_returnsEmpty() {
        var result = subject.render("class Demo {}", "");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Unsupported language keeps fallback code visible")
    void render_whenLanguageUnsupported_returnsEmpty() {
        var result = subject.render("graph TD;", "mermaid");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Render cache is bounded")
    void render_whenManyUniqueCodeBlocksProvided_keepsCacheBounded() {
        for (int i = 0; i < 600; i++) {
            subject.render("class Demo%d {}".formatted(i), "java");
        }

        assertThat(subject.cacheSize()).isLessThanOrEqualTo(512);
    }

    @Test
    @DisplayName("Common language aliases normalize to bundled Highlight.js languages")
    void normalizeLanguage_whenAliasProvided_returnsCanonicalLanguage() {
        assertThat(subject.normalizeLanguage("js")).isEqualTo("javascript");
        assertThat(subject.normalizeLanguage("ts")).isEqualTo("typescript");
        assertThat(subject.normalizeLanguage("sh")).isEqualTo("bash");
        assertThat(subject.normalizeLanguage("md")).isEqualTo("markdown");
        assertThat(subject.normalizeLanguage("html")).isEqualTo("xml");
    }
}
