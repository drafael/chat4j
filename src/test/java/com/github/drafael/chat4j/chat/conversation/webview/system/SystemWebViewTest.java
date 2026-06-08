package com.github.drafael.chat4j.chat.conversation.webview.system;

import com.github.drafael.chat4j.chat.conversation.ConversationAttachment;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SystemWebViewTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Attachment strip renders image previews and file chips")
    void renderAttachmentStripHtml_whenAttachmentsPresent_rendersPreviewAndChip() throws Exception {
        Path imagePath = tempDir.resolve("photo.png");
        writePng(imagePath);
        Path filePath = tempDir.resolve("demo.txt");
        Files.writeString(filePath, "attachment");

        String html = SystemWebView.renderAttachmentStripHtml(List.of(
                new ConversationAttachment(
                        imagePath.toString(),
                        "photo \"quoted\".png",
                        "image/png",
                        256,
                        true
                ),
                new ConversationAttachment(
                        filePath.toString(),
                        "demo <file>.txt",
                        "text/plain",
                        128,
                        false
                )
        ));
        var document = Jsoup.parseBodyFragment(html);

        assertThat(document.select(".attachment-strip")).hasSize(1);
        assertThat(document.select(".attachment-image")).hasSize(1);
        assertThat(document.select(".attachment-image").attr("src")).startsWith("data:image/png;base64,");
        assertThat(document.select(".attachment-image-button").attr("data-attachment-path")).isEqualTo(imagePath.toString());
        assertThat(document.select(".attachment-chip:not(.unavailable)")).hasSize(1);
        assertThat(document.select(".attachment-chip").text()).contains("demo <file>.txt", "128 B");
        assertThat(html).contains("photo &quot;quoted&quot;.png", "demo &lt;file&gt;.txt");
        assertThat(html).doesNotContain("file://");
    }

    @Test
    @DisplayName("Missing image attachments render a fallback chip")
    void renderAttachmentStripHtml_whenImageMissing_rendersFallbackChip() {
        String html = SystemWebView.renderAttachmentStripHtml(List.of(
                new ConversationAttachment(
                        tempDir.resolve("missing.png").toString(),
                        "missing.png",
                        "image/png",
                        256,
                        true
                )
        ));
        var document = Jsoup.parseBodyFragment(html);

        assertThat(document.select(".attachment-image")).isEmpty();
        assertThat(document.select(".attachment-chip.unavailable")).hasSize(1);
        assertThat(document.select(".attachment-chip").text()).contains("missing.png", "256 B");
    }

    @Test
    @DisplayName("Math stylesheet is bundled into the WebView document head")
    void mathHeadAssets_whenRendered_containsBundledKatexStylesheet() {
        String assets = SystemWebView.mathHeadAssets();

        assertThat(assets)
                .contains("chat4j-katex-css")
                .contains("data:font/woff2;base64,")
                .doesNotContain("cdn.jsdelivr")
                .doesNotContain("unpkg.com");
    }

    @Test
    @DisplayName("Math bridge script bundles KaTeX mhchem and the renderer")
    void mathBridgeScript_whenRendered_containsBundledKatexAndRenderer() {
        String script = SystemWebView.mathBridgeScript();

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
        String script = SystemWebView.mathBridgeScript();

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

        SystemWebView.renderMathFallbacks(document);

        assertThat(document.select("code.md-latex-inline")).isEmpty();
        assertThat(document.select("table.md-latex-block")).isEmpty();
        assertThat(document.select(".chat4j-math-inline .katex")).hasSize(1);
        assertThat(document.select(".chat4j-math-display .katex-display")).hasSize(1);
    }

    @Test
    @DisplayName("Adjacent duplicate source citations are collapsed")
    void removeAdjacentDuplicateSourceCitations_whenSameCitationRepeats_removesDuplicateAnchor() {
        var document = Jsoup.parse("""
                <html><body><p>
                  Text <a class="source-citation" href="https://example.com/a" data-source-url="https://example.com/a">9</a>
                  <a class="source-citation" href="https://example.com/a" data-source-url="https://example.com/a">9</a>
                  next <a class="source-citation" href="https://example.com/b" data-source-url="https://example.com/b">10</a>
                </p></body></html>
                """);

        SystemWebView.removeAdjacentDuplicateSourceCitations(document);

        assertThat(document.select("a.source-citation")).hasSize(2);
        assertThat(document.select("a.source-citation").eachText()).containsExactly("9", "10");
    }

    @Test
    @DisplayName("Supported code blocks are highlighted before WebView load")
    void renderCodeHighlights_whenDocumentContainsSupportedCodeBlock_replacesPreHtmlWithHighlightSpans() {
        var document = Jsoup.parse("""
                <html><body>
                  <table class=\"md-code-block\" data-code-language=\"java\"><tr><td>java</td></tr><tr><td><pre>class Demo { String value = &quot;ok&quot;; }</pre></td></tr></table>
                </body></html>
                """);

        SystemWebView.renderCodeHighlights(document);

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

        SystemWebView.renderCodeHighlights(document);

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

        SystemWebView.renderCodeHighlights(document);

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

        SystemWebView.renderCodeHighlights(document);

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

        SystemWebView.renderCodeHighlights(document);

        assertThat(document.select("pre.hljs.language-markdown")).hasSize(1);
        assertThat(document.select("pre .hljs-section")).isNotEmpty();
    }

    private static void writePng(Path path) throws Exception {
        BufferedImage image = new BufferedImage(24, 12, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
    }
}
