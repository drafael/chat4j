package com.github.drafael.chat4j.chat.message;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SwingWebViewTranscriptViewTest {

    @Test
    @DisplayName("Math stylesheet is bundled into the WebView document head")
    void mathHeadAssets_whenRendered_containsBundledKatexStylesheet() {
        String assets = SwingWebViewTranscriptView.mathHeadAssets();

        assertThat(assets)
                .contains("chat4j-katex-css")
                .contains("data:font/woff2;base64,")
                .doesNotContain("cdn.jsdelivr")
                .doesNotContain("unpkg.com");
    }

    @Test
    @DisplayName("Math bridge script bundles KaTeX mhchem and the renderer")
    void mathBridgeScript_whenRendered_containsBundledKatexAndRenderer() {
        String script = SwingWebViewTranscriptView.mathBridgeScript();

        assertThat(script)
                .contains("katex")
                .contains("mhchem")
                .contains("chat4jRenderMath")
                .doesNotContain("cdn.jsdelivr")
                .doesNotContain("unpkg.com");
    }

    @Test
    @DisplayName("Math renderer only targets explicit LaTeX fallback elements")
    void mathBridgeScript_whenRendered_targetsFallbackMathElementsOnly() {
        String script = SwingWebViewTranscriptView.mathBridgeScript();

        assertThat(script)
                .contains("code.md-latex-inline:not([data-chat4j-math-rendered])")
                .contains("table.md-latex-block:not([data-chat4j-math-rendered])")
                .contains("throwOnError: false")
                .contains("trust: false");
    }

    @Test
    @DisplayName("Math fallback nodes are replaced with server-rendered KaTeX before WebView load")
    void renderMathFallbacks_whenDocumentContainsMath_replacesFallbackNodesWithKatexHtml() {
        var document = Jsoup.parse("""
                <html><body>
                  <p>Use <code class=\"md-latex-inline\">$\\ce{CO2}$</code>.</p>
                  <table class=\"md-code-block md-latex-block\"><tr><td><pre>m = \\frac{Q}{F}</pre></td></tr></table>
                </body></html>
                """);

        SwingWebViewTranscriptView.renderMathFallbacks(document);

        assertThat(document.select("code.md-latex-inline")).isEmpty();
        assertThat(document.select("table.md-latex-block")).isEmpty();
        assertThat(document.select(".chat4j-math-inline .katex")).hasSize(1);
        assertThat(document.select(".chat4j-math-display .katex-display")).hasSize(1);
    }

    @Test
    @DisplayName("Supported code blocks are highlighted before WebView load")
    void renderCodeHighlights_whenDocumentContainsSupportedCodeBlock_replacesPreHtmlWithHighlightSpans() {
        var document = Jsoup.parse("""
                <html><body>
                  <table class=\"md-code-block\" data-code-language=\"java\"><tr><td>java</td></tr><tr><td><pre>class Demo { String value = &quot;ok&quot;; }</pre></td></tr></table>
                </body></html>
                """);

        SwingWebViewTranscriptView.renderCodeHighlights(document);

        assertThat(document.select("pre.hljs.language-java")).hasSize(1);
        assertThat(document.select("pre .hljs-keyword")).isNotEmpty();
        assertThat(document.select("pre .hljs-string")).isNotEmpty();
    }

    @Test
    @DisplayName("Unlabelled code blocks stay plain")
    void renderCodeHighlights_whenDocumentContainsUnlabelledCodeBlock_keepsFallbackMarkup() {
        var document = Jsoup.parse("""
                <html><body>
                  <table class=\"md-code-block\"><tr><td><pre>class Demo {}</pre></td></tr></table>
                </body></html>
                """);

        SwingWebViewTranscriptView.renderCodeHighlights(document);

        assertThat(document.select("pre.hljs")).isEmpty();
        assertThat(document.select("pre").text()).contains("class Demo");
    }

    @Test
    @DisplayName("Unsupported languages stay plain")
    void renderCodeHighlights_whenDocumentContainsUnsupportedLanguage_keepsFallbackMarkup() {
        var document = Jsoup.parse("""
                <html><body>
                  <table class=\"md-code-block\" data-code-language=\"mermaid\"><tr><td>mermaid</td></tr><tr><td><pre>graph TD;</pre></td></tr></table>
                </body></html>
                """);

        SwingWebViewTranscriptView.renderCodeHighlights(document);

        assertThat(document.select("pre.hljs")).isEmpty();
        assertThat(document.select("pre").text()).contains("graph TD");
    }

    @Test
    @DisplayName("LaTeX fallback blocks are not syntax-highlighted")
    void renderCodeHighlights_whenDocumentContainsLatexFallback_keepsMathBlockForMathRenderer() {
        var document = Jsoup.parse("""
                <html><body>
                  <table class=\"md-code-block md-latex-block\" data-code-language=\"latex\"><tr><td>latex</td></tr><tr><td><pre>m = \\frac{Q}{F}</pre></td></tr></table>
                </body></html>
                """);

        SwingWebViewTranscriptView.renderCodeHighlights(document);

        assertThat(document.select("pre.hljs")).isEmpty();
        assertThat(document.select("table.md-latex-block")).hasSize(1);
    }

    @Test
    @DisplayName("Markdown source blocks are highlighted when explicitly labelled")
    void renderCodeHighlights_whenDocumentContainsMarkdownSource_highlightsMarkdownTokens() {
        var document = Jsoup.parse("""
                <html><body>
                  <table class=\"md-code-block\" data-code-language=\"markdown\"><tr><td>markdown</td></tr><tr><td><pre># Title\n\n```java\nclass Demo {}\n```</pre></td></tr></table>
                </body></html>
                """);

        SwingWebViewTranscriptView.renderCodeHighlights(document);

        assertThat(document.select("pre.hljs.language-markdown")).hasSize(1);
        assertThat(document.select("pre .hljs-section")).isNotEmpty();
    }
}
