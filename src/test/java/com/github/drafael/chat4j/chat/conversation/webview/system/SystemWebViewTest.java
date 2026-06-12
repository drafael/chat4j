package com.github.drafael.chat4j.chat.conversation.webview.system;

import com.github.drafael.chat4j.chat.content.MessageHtmlRenderer;
import com.github.drafael.chat4j.chat.conversation.ConversationAttachment;
import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.provider.api.Role;
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
    @DisplayName("Math and diagram assets are bundled into the WebView document head")
    void mathHeadAssets_whenRendered_containsBundledDiagramScripts() {
        String assets = SystemWebView.mathHeadAssets();

        assertThat(assets)
                .contains("chat4j-mermaid-script")
                .contains("chat4j-smiles-drawer-script")
                .contains("chat4j-diagram-render-script")
                .contains("chat4jRenderEnhancements")
                .doesNotContain("cdn.jsdelivr")
                .doesNotContain("unpkg.com");
    }

    @Test
    @DisplayName("Math bridge script bundles KaTeX mhchem diagrams and the renderers")
    void mathBridgeScript_whenRendered_containsBundledKatexDiagramsAndRenderers() {
        String script = SystemWebView.mathBridgeScript();

        assertThat(script)
                .contains("katex")
                .contains("mhchem")
                .contains("mermaid")
                .contains("SmilesDrawer")
                .contains("scale: 1.35")
                .contains("parseMolV2000")
                .contains("renderMolLikeBlock")
                .contains("SDF_MAX_RECORDS = 12")
                .contains("Showing first ")
                .contains("chat4jRenderMath")
                .contains("chat4jRenderDiagrams")
                .contains("chat4jRenderEnhancements")
                .doesNotContain("cdn.jsdelivr")
                .doesNotContain("unpkg.com");
    }

    @Test
    @DisplayName("Diagram bridge can open rendered Mermaid diagrams externally")
    void mathBridgeScript_whenRendered_containsOpenMermaidDiagramAction() {
        String script = SystemWebView.mathBridgeScript();

        assertThat(script)
                .contains("open-diagram-html")
                .contains("XMLSerializer")
                .contains("chat4j-mermaid-display")
                .contains("diagram-open-button")
                .contains("window.chat4jDispatchTranscriptAction('open-diagram-html', -1, payload)")
                .contains("window.chat4jOpenMermaidDiagram = openMermaidDiagram")
                .contains("Open diagram");
    }

    @Test
    @DisplayName("Transcript bridge exposes the shared action dispatcher for rendered diagrams")
    void bridgeScript_whenRendered_exposesDiagramActionDispatcher() {
        String script = SystemWebView.bridgeScript();

        assertThat(script)
                .contains("window.chat4jDispatchTranscriptAction = dispatchTranscriptAction")
                .contains("window.chat4jOpenMermaidDiagram(menu._chat4jDiagram)")
                .contains("data-action=\"open-diagram\"")
                .contains("Open Diagram");
    }

    @Test
    @DisplayName("Diagram bridge preserves source blocks on rendering errors")
    void mathBridgeScript_whenRendered_preservesDiagramSourceOnError() {
        String script = SystemWebView.mathBridgeScript();

        assertThat(script)
                .contains("table.insertRow(0)")
                .contains("chat4j-diagram-error-badge")
                .contains("target.parentNode.replaceChild(originalNode, target)")
                .contains("window.mermaid.parse(candidate)")
                .contains("repairMermaidSource(source)")
                .contains("renderSource(repaired, '-repaired')")
                .contains("mermaidErrorSvg(svg)")
                .contains("friendlyDiagramError")
                .contains("Mermaid syntax error — source shown below")
                .contains("Mermaid renderer unavailable")
                .contains("Mermaid render timed out")
                .contains("SMILES renderer unavailable")
                .contains("SMILES render failed")
                .contains("MOL must be complete V2000 source — source shown below")
                .contains("SDF must be complete V2000 source — source shown below")
                .contains("chat4j-chem-record-summary");
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
    @DisplayName("Math fallback rendering handles nested-dollar text formulas")
    void renderMathFallbacks_whenInlineMathContainsNestedTextDollars_replacesFallbackNode() {
        var document = Jsoup.parse("""
                <html><body>
                  <p>Use <code class=\"md-latex-inline\">$\\text{Drawings often use $\\pi$ notation}$</code>.</p>
                </body></html>
                """);

        SystemWebView.renderMathFallbacks(document);

        assertThat(document.select("code.md-latex-inline")).isEmpty();
        assertThat(document.select(".chat4j-math-inline .katex")).hasSize(1);
        assertThat(document.text()).contains("Drawings often use", "π", "notation");
    }

    @Test
    @DisplayName("Math fallback rendering handles display arrays with lenient KaTeX strict mode")
    void renderMathFallbacks_whenDisplayArrayHasLooseColumnSpec_replacesFallbackNode() {
        var document = Jsoup.parse("""
                <html><body>
                  <table class=\"md-code-block md-latex-block\"><tr><td><pre>\\begin{array}{c} \\text{H} &amp; \\text{H} \\\\ \\text{H} &amp; \\text{C} &amp; \\text{H} \\end{array}</pre></td></tr></table>
                </body></html>
                """);

        SystemWebView.renderMathFallbacks(document);

        assertThat(document.select("table.md-latex-block")).isEmpty();
        assertThat(document.select(".chat4j-math-display .katex-display")).hasSize(1);
    }

    @Test
    @DisplayName("Math fallback rendering handles table formulas with vertical bond pipes")
    void renderMathFallbacks_whenTableFormulaContainsVerticalBondPipes_replacesFallbackNode() {
        String html = new MessageHtmlRenderer().render(Role.ASSISTANT, RenderMode.PREVIEW, """
                | Convention | What it shows | Example Molecule | Visual Representation | Best Used For... |
                | :--- | :--- | :--- | :--- | :--- |
                | **Lewis Structure** | Shows *every* atom and *every* bond. | $\\text{CH}_4$ | $$\\begin{array}{c} \\text{H} & \\text{H} \\\\ | & | \\\\ \\text{H} - \\text{C} - \\text{H} \\\\ | & | \\\\ \\text{H} & \\text{H} \\end{array}$$ | Teaching fundamentals; showing lone pairs. |
                """, false);
        var document = Jsoup.parse(html);

        SystemWebView.renderMathFallbacks(document);

        var dataRowCells = document.select("table.md-table tr").get(1).select("td");
        var visualCell = dataRowCells.get(3);
        assertThat(dataRowCells).hasSize(5);
        assertThat(visualCell.select("code.md-latex-inline")).isEmpty();
        assertThat(visualCell.select(".chat4j-math-inline .katex-display")).hasSize(1);
        assertThat(dataRowCells.get(4).text()).contains("Teaching fundamentals");
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
    @DisplayName("Diagram blocks are not syntax-highlighted before browser rendering")
    void renderCodeHighlights_whenDocumentContainsDiagramBlock_keepsDiagramBlockForBrowserRenderer() {
        var document = Jsoup.parse("""
                <html><body>
                  <table class=\"md-code-block md-diagram-block md-mermaid-block\" data-code-language=\"mermaid\"><tr><td>mermaid</td></tr><tr><td><pre>flowchart TD\nA --> B</pre></td></tr></table>
                </body></html>
                """);

        SystemWebView.renderCodeHighlights(document);

        assertThat(document.select("pre.hljs")).isEmpty();
        assertThat(document.select("table.md-diagram-block")).hasSize(1);
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
