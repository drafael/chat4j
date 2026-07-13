package com.github.drafael.chat4j.chat.conversation.webview.shared;

import com.github.drafael.chat4j.chat.content.MessageHtmlRenderer;
import com.github.drafael.chat4j.chat.conversation.ConversationAttachment;
import com.github.drafael.chat4j.chat.conversation.ConversationEntry;
import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.MessageMeta;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptDocumentRendererTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Attachment strip renders image previews and file chips")
    void renderAttachmentStripHtml_whenAttachmentsPresent_rendersPreviewAndChip() throws Exception {
        Path imagePath = tempDir.resolve("photo.png");
        writePng(imagePath);
        Path filePath = tempDir.resolve("demo.txt");
        Files.writeString(filePath, "attachment");

        String html = TranscriptDocumentRenderer.renderAttachmentStripHtml(List.of(
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
        String html = TranscriptDocumentRenderer.renderAttachmentStripHtml(List.of(
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
    @DisplayName("Generated images use embedded data URLs before WebView load")
    void replaceGeneratedImageSources_whenGeneratedImageUsesFileUri_embedsThumbnailDataUri() throws Exception {
        Path imagePath = tempDir.resolve("generated.png");
        writePng(imagePath, 1000, 700);
        var attachmentRef = new AttachmentRef(UUID.randomUUID(), imagePath.toString(), "generated.png", "image/png", Files.size(imagePath), "sha");
        String html = new MessageHtmlRenderer().render(
                Role.ASSISTANT,
                RenderMode.PREVIEW,
                List.of(new GeneratedImagePart(attachmentRef, 40, 30, "Generated image")),
                false
        );
        var document = Jsoup.parse(html);

        assertThat(document.selectFirst("img.generated-image").attr("src")).startsWith("file:");

        TranscriptEntryRenderer.replaceGeneratedImageSources(document);

        String src = document.selectFirst("img.generated-image").attr("src");
        assertThat(src).startsWith("data:image/png;base64,");
        BufferedImage embeddedImage = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(src.substring("data:image/png;base64,".length()))));
        assertThat(embeddedImage.getWidth()).isGreaterThan(420);
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

        TranscriptDocumentRenderer.renderMathFallbacks(document);

        assertThat(document.select("code.md-latex-inline")).isEmpty();
        assertThat(document.select("table.md-latex-block")).isEmpty();
        assertThat(document.select(".chat4j-math-inline .katex")).hasSize(1);
        assertThat(document.select(".chat4j-math-display .katex-display")).hasSize(1);
    }

    @Test
    @DisplayName("Numeric inline math is server-rendered before WebView load")
    void renderMathFallbacks_whenNumericInlineMathIsRendered_replacesFallbackNodes() {
        String rendered = new MessageHtmlRenderer().render(
                Role.ASSISTANT,
                RenderMode.PREVIEW,
                "Use $10\\text{g}$ of salt, about $1-2\\text{g}$, for $10-15$ minutes.",
                false
        );
        var document = Jsoup.parse(rendered);

        TranscriptDocumentRenderer.renderMathFallbacks(document);

        assertThat(document.select("code.md-latex-inline")).isEmpty();
        assertThat(document.select(".chat4j-math-inline .katex")).hasSize(3);
    }

    @Test
    @DisplayName("Math fallback rendering handles nested-dollar text formulas")
    void renderMathFallbacks_whenInlineMathContainsNestedTextDollars_replacesFallbackNode() {
        var document = Jsoup.parse("""
                <html><body>
                  <p>Use <code class=\"md-latex-inline\">$\\text{Drawings often use $\\pi$ notation}$</code>.</p>
                </body></html>
                """);

        TranscriptDocumentRenderer.renderMathFallbacks(document);

        assertThat(document.select("code.md-latex-inline")).isEmpty();
        assertThat(document.select(".chat4j-math-inline .katex")).hasSize(1);
        assertThat(document.text()).contains("Drawings often use", "π", "notation");
    }

    @Test
    @DisplayName("Unrenderable math fallbacks become readable text instead of raw LaTeX chips")
    void renderMathFallbacks_whenKatexCannotRender_replacesFallbackNodesWithReadableText() {
        var document = Jsoup.parse("""
                <html><body>
                  <p>Unknown inline <code class=\"md-latex-inline\">$\\bad{value$</code>.</p>
                  <table class=\"md-code-block md-latex-block\"><tr><td><pre>\\bad{display</pre></td></tr></table>
                </body></html>
                """);

        TranscriptDocumentRenderer.renderMathFallbacks(document);

        assertThat(document.select("code.md-latex-inline")).isEmpty();
        assertThat(document.select("table.md-latex-block")).isEmpty();
        assertThat(document.select("[data-chat4j-math-rendered=text-fallback]")).hasSize(2);
        assertThat(document.text()).contains("value", "display").doesNotContain("$\\bad{value$");
    }

    @Test
    @DisplayName("Math fallback rendering handles display arrays with lenient KaTeX strict mode")
    void renderMathFallbacks_whenDisplayArrayHasLooseColumnSpec_replacesFallbackNode() {
        var document = Jsoup.parse("""
                <html><body>
                  <table class=\"md-code-block md-latex-block\"><tr><td><pre>\\begin{array}{c} \\text{H} &amp; \\text{H} \\\\ \\text{H} &amp; \\text{C} &amp; \\text{H} \\end{array}</pre></td></tr></table>
                </body></html>
                """);

        TranscriptDocumentRenderer.renderMathFallbacks(document);

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

        TranscriptDocumentRenderer.renderMathFallbacks(document);

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

        TranscriptDocumentRenderer.removeAdjacentDuplicateSourceCitations(document);

        assertThat(document.select("a.source-citation")).hasSize(2);
        assertThat(document.select("a.source-citation").eachText()).containsExactly("9", "10");
    }

    @Test
    @DisplayName("Metadata-backed document citations render source preview attributes")
    void renderEntriesHtml_whenDocumentCitationMetadataPresent_replacesPlainMarkerWithPreviewCitation() {
        var citation = CitationRef.builder()
                .number(1)
                .kind(CitationKind.DOCUMENT_PAGE)
                .title("earth.pdf")
                .citedText("Earth is the third planet from the Sun.")
                .documentIndex(0L)
                .documentTitle("earth.pdf")
                .fileId("file_123")
                .startPage(4L)
                .endPage(5L)
                .build();
        var entry = ConversationEntry.message(
                Role.ASSISTANT,
                "Earth facts [1]",
                0,
                List.of(),
                List.of(),
                new MessageMeta(List.of(), List.of(), false, "", "", "", List.of(), List.of(citation))
        );

        String html = new TranscriptEntryRenderer().renderEntriesHtml(TranscriptRenderSupport.snapshot(List.of(entry), RenderMode.PREVIEW, false, false));
        var document = Jsoup.parseBodyFragment(html);
        var anchor = document.selectFirst("a.source-citation.citation-ref");

        assertThat(anchor).isNotNull();
        assertThat(anchor.text()).isEqualTo("1");
        assertThat(anchor.hasAttr("href")).isFalse();
        assertThat(anchor.attr("data-source-title")).isEqualTo("earth.pdf");
        assertThat(anchor.attr("data-source-domain")).isEqualTo("pages 4-5");
        assertThat(anchor.attr("data-source-snippet")).contains("Earth is the third planet");
    }

    @Test
    @DisplayName("Metadata-backed web citations keep URL navigation and preview attributes")
    void renderEntriesHtml_whenWebCitationMetadataPresent_annotatesMarkdownCitationWithPreviewData() {
        var citation = CitationRef.builder()
                .number(1)
                .kind(CitationKind.WEB)
                .title("Example Source")
                .citedText("Quoted web text")
                .url("https://example.com/source")
                .encryptedIndex("enc")
                .build();
        var entry = ConversationEntry.message(
                Role.ASSISTANT,
                "Fact [1](https://example.com/source)",
                0,
                List.of(),
                List.of(),
                new MessageMeta(List.of(), List.of(), false, "", "", "", List.of(), List.of(citation))
        );

        String html = new TranscriptEntryRenderer().renderEntriesHtml(TranscriptRenderSupport.snapshot(List.of(entry), RenderMode.PREVIEW, false, false));
        var document = Jsoup.parseBodyFragment(html);
        var anchor = document.selectFirst("a.source-citation.citation-ref");

        assertThat(anchor).isNotNull();
        assertThat(anchor.attr("href")).isEqualTo("https://example.com/source");
        assertThat(anchor.attr("data-source-title")).isEqualTo("Example Source");
        assertThat(anchor.attr("data-source-domain")).isEqualTo("example.com");
        assertThat(anchor.attr("data-source-snippet")).isEqualTo("Quoted web text");
    }

    @Test
    @DisplayName("Metadata-backed source lists render readable labels instead of raw URLs")
    void renderEntriesHtml_whenSourceListUsesMarkdownLabel_keepsRawUrlOutOfVisibleText() {
        var citation = CitationRef.builder()
                .number(1)
                .kind(CitationKind.WEB)
                .title("Example Source")
                .url("https://vertexaisearch.cloud.google.com/grounding-api-redirect/very-long-token")
                .build();
        var entry = ConversationEntry.message(
                Role.ASSISTANT,
                """
                        Fact [1]

                        Sources:
                        [1] [Example Source](<https://vertexaisearch.cloud.google.com/grounding-api-redirect/very-long-token>)
                        """,
                0,
                List.of(),
                List.of(),
                new MessageMeta(List.of(), List.of(), false, "", "", "", List.of(), List.of(citation))
        );

        String html = new TranscriptEntryRenderer().renderEntriesHtml(TranscriptRenderSupport.snapshot(List.of(entry), RenderMode.PREVIEW, false, false));
        var document = Jsoup.parseBodyFragment(html);

        assertThat(document.select("a.source-citation.citation-ref").eachText()).containsExactly("1", "1");
        assertThat(document.select("a.source-link").eachText()).contains("Example Source");
        assertThat(document.body().text()).doesNotContain("https://vertexaisearch.cloud.google.com/grounding-api-redirect/very-long-token");
    }

    @Test
    @DisplayName("Unsafe metadata-backed web citation URLs do not become navigable links")
    void renderEntriesHtml_whenWebCitationUrlIsNotHttp_removesHref() {
        var citation = CitationRef.builder()
                .number(1)
                .kind(CitationKind.WEB)
                .title("Unsafe Source")
                .citedText("Quoted text")
                .url("javascript:alert(1)")
                .build();
        var entry = ConversationEntry.message(
                Role.ASSISTANT,
                "Fact [1]",
                0,
                List.of(),
                List.of(),
                new MessageMeta(List.of(), List.of(), false, "", "", "", List.of(), List.of(citation))
        );

        String html = new TranscriptEntryRenderer().renderEntriesHtml(TranscriptRenderSupport.snapshot(List.of(entry), RenderMode.PREVIEW, false, false));
        var document = Jsoup.parseBodyFragment(html);
        var anchor = document.selectFirst("a.source-citation.citation-ref");

        assertThat(anchor).isNotNull();
        assertThat(anchor.hasAttr("href")).isFalse();
        assertThat(anchor.attr("data-source-url")).isEqualTo("citation:1");
    }

    @Test
    @DisplayName("Supported code blocks are highlighted before WebView load")
    void renderCodeHighlights_whenDocumentContainsSupportedCodeBlock_replacesPreHtmlWithHighlightSpans() {
        var document = Jsoup.parse("""
                <html><body>
                  <table class=\"md-code-block\" data-code-language=\"java\"><tr><td>java</td></tr><tr><td><pre>class Demo { String value = &quot;ok&quot;; }</pre></td></tr></table>
                </body></html>
                """);

        TranscriptDocumentRenderer.renderCodeHighlights(document);

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

        TranscriptDocumentRenderer.renderCodeHighlights(document);

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

        TranscriptDocumentRenderer.renderCodeHighlights(document);

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

        TranscriptDocumentRenderer.renderCodeHighlights(document);

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

        TranscriptDocumentRenderer.renderCodeHighlights(document);

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

        TranscriptDocumentRenderer.renderCodeHighlights(document);

        assertThat(document.select("pre.hljs.language-markdown")).hasSize(1);
        assertThat(document.select("pre .hljs-section")).isNotEmpty();
    }

    private static void writePng(Path path) throws Exception {
        writePng(path, 24, 12);
    }

    private static void writePng(Path path, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
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
