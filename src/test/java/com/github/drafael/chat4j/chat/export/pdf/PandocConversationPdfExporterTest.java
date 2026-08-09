package com.github.drafael.chat4j.chat.export.pdf;

import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PandocConversationPdfExporterTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("Publication Markdown keeps literal code intact and emits provider metadata once")
    void renderMarkdown_whenContentContainsMarkdownSyntax_preservesSourceForPandocFiltering() throws Exception {
        var subject = new PandocConversationPdfExporter("pandoc", "lualatex", Map.of());
        var document = ConversationPdfDocument.builder()
                .title("Publication \"report\"\nSecond line")
                .provider("Anthropic")
                .model("model")
                .createdAt(LocalDateTime.parse("2026-08-08T08:30:15"))
                .exportedAt(Instant.parse("2026-08-08T10:00:00Z"))
                .turns(List.of(new ConversationPdfDocument.Turn(
                        Role.ASSISTANT,
                        Instant.parse("2026-08-08T09:00:00Z"),
                        List.of(new TextPart("""
                                ```markdown
                                ![literal](/code/example.png)
                                [literal](javascript:example)
                                ```

                                ![remote](https://example.com/image.png)
                                """)),
                        List.of(),
                        false,
                        "",
                        List.of()
                )))
                .build();

        String markdown = subject.renderMarkdown(document, tempDirectory);
        String expectedTimestamp = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .format(document.turns().getFirst().timestamp().atZone(ZoneId.systemDefault()));

        assertThat(markdown)
                .contains("title: \"Publication \\\"report\\\"\\nSecond line\"")
                .contains("date: \"")
                .contains("**Created:** ")
                .contains("*%s*".formatted(expectedTimestamp))
                .doesNotContain("2026-08-08T10:00:00Z")
                .doesNotContain("2026-08-08T09:00:00Z")
                .doesNotContain("2026-08-08T08:30:15")
                .contains("![literal](/code/example.png)")
                .contains("[literal](javascript:example)")
                .contains("![remote](https://example.com/image.png)");
        assertThat(countOccurrences(markdown, "subtitle:")).isEqualTo(1);
        assertThat(markdown).doesNotContain("**Provider and model:**");
    }

    @Test
    @DisplayName("Publication rendering replaces corrupt managed images with a placeholder")
    void renderMarkdown_whenManagedImageIsCorrupt_doesNotPassItToLatex() throws Exception {
        Path corruptImage = tempDirectory.resolve("corrupt.png");
        Files.write(corruptImage, new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        });
        var reference = new AttachmentRef(
                UUID.randomUUID(),
                corruptImage.toString(),
                "corrupt.png",
                "image/png",
                Files.size(corruptImage),
                ""
        );
        var document = ConversationPdfDocument.builder()
                .title("Publication")
                .exportedAt(Instant.EPOCH)
                .turns(List.of(new ConversationPdfDocument.Turn(
                        Role.ASSISTANT,
                        Instant.EPOCH,
                        List.of(new GeneratedImagePart(reference, null, null, "Broken image")),
                        List.of(),
                        false,
                        "",
                        List.of()
                )))
                .build();
        var subject = new PandocConversationPdfExporter("pandoc", "lualatex", Map.of());

        String markdown = subject.renderMarkdown(document, tempDirectory);

        assertThat(markdown)
                .contains("Image unavailable: Broken image")
                .doesNotContain("image-0-0.png");
    }

    @Test
    @DisplayName("Publication staging uses decoded image format instead of an incorrect declared MIME type")
    void renderMarkdown_whenImageMimeTypeIsWrong_usesActualSafeExtension() throws Exception {
        Path mislabeledImage = tempDirectory.resolve("mislabeled.png");
        var image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        assertThat(ImageIO.write(image, "jpeg", mislabeledImage.toFile())).isTrue();
        var reference = new AttachmentRef(
                UUID.randomUUID(),
                mislabeledImage.toString(),
                "mislabeled.png",
                "image/png",
                Files.size(mislabeledImage),
                ""
        );
        var document = ConversationPdfDocument.builder()
                .title("Publication")
                .exportedAt(Instant.EPOCH)
                .turns(List.of(new ConversationPdfDocument.Turn(
                        Role.ASSISTANT,
                        Instant.EPOCH,
                        List.of(new GeneratedImagePart(reference, 40, 30, "Mislabeled image")),
                        List.of(),
                        false,
                        "",
                        List.of()
                )))
                .build();
        var subject = new PandocConversationPdfExporter("pandoc", "lualatex", Map.of());

        String markdown = subject.renderMarkdown(document, tempDirectory);

        assertThat(markdown).contains("![Mislabeled image](image-0-0.jpg)");
        assertThat(tempDirectory.resolve("image-0-0.jpg")).hasSameBinaryContentAs(mislabeledImage);
        assertThat(tempDirectory.resolve("image-0-0.png")).doesNotExist();
    }

    @Test
    @DisplayName("Publication staging scales images that exceed the demonstrated LaTeX dimension boundary")
    void renderMarkdown_whenImageExceedsLatexDimension_scalesWithinBoundary() throws Exception {
        Path tallImage = tempDirectory.resolve("tall.png");
        assertThat(ImageIO.write(
                new BufferedImage(100, 17_000, BufferedImage.TYPE_INT_RGB),
                "png",
                tallImage.toFile()
        )).isTrue();
        var reference = new AttachmentRef(
                UUID.randomUUID(),
                tallImage.toString(),
                "tall.png",
                "image/png",
                Files.size(tallImage),
                ""
        );
        var document = ConversationPdfDocument.builder()
                .title("Publication")
                .exportedAt(Instant.EPOCH)
                .turns(List.of(new ConversationPdfDocument.Turn(
                        Role.ASSISTANT,
                        Instant.EPOCH,
                        List.of(new GeneratedImagePart(reference, 100, 17_000, "Tall image")),
                        List.of(),
                        false,
                        "",
                        List.of()
                )))
                .build();
        var subject = new PandocConversationPdfExporter("pandoc", "lualatex", Map.of());

        String markdown = subject.renderMarkdown(document, tempDirectory);
        BufferedImage staged = ImageIO.read(tempDirectory.resolve("image-0-0.png").toFile());

        assertThat(markdown).contains("![Tall image](image-0-0.png)");
        assertThat(staged.getWidth()).isLessThanOrEqualTo(PandocConversationPdfExporter.MAX_LATEX_IMAGE_DIMENSION);
        assertThat(staged.getHeight()).isEqualTo(PandocConversationPdfExporter.MAX_LATEX_IMAGE_DIMENSION);
    }

    @Test
    @DisplayName("Publication Markdown normalizes common backslash math delimiters without changing code")
    void renderMarkdown_whenBackslashMathDelimitersAreUsed_normalizesForPandocGfm() throws Exception {
        var document = ConversationPdfDocument.builder()
                .title("Math delimiters")
                .exportedAt(Instant.EPOCH)
                .turns(List.of(new ConversationPdfDocument.Turn(
                        Role.ASSISTANT,
                        Instant.EPOCH,
                        List.of(new TextPart("""
                                \\[
                                \\boxed{\\mathcal{E} = -\\frac{d\\Phi_B}{dt}}
                                \\]

                                Inline \\( N \\) and `\\(literal code\\)`.
                                """)),
                        List.of(),
                        false,
                        "",
                        List.of()
                )))
                .build();
        var subject = new PandocConversationPdfExporter("pandoc", "lualatex", Map.of());

        assertThat(subject.renderMarkdown(document, tempDirectory))
                .contains("$$\n\\boxed{\\mathcal{E} = -\\frac{d\\Phi_B}{dt}}\n$$")
                .contains("Inline $N$ and `\\(literal code\\)`.");
    }

    @Test
    @DisplayName("Publication Markdown replaces valid SMILES blocks with managed diagrams and exact captions")
    void renderMarkdown_whenSmilesIsValid_writesManagedDiagramAndCaption() throws Exception {
        String smiles = "CC(=O)Oc1ccccc1C(=O)O";
        var document = ConversationPdfDocument.builder()
                .title("Publication")
                .exportedAt(Instant.EPOCH)
                .turns(List.of(new ConversationPdfDocument.Turn(
                        Role.ASSISTANT,
                        Instant.EPOCH,
                        List.of(new TextPart("""
                                Aspirin description.
                                ```SMILES
                                %s
                                ```
                                Following prose.
                                """.formatted(smiles))),
                        List.of(),
                        false,
                        "",
                        List.of()
                )))
                .build();
        var subject = new PandocConversationPdfExporter("pandoc", "lualatex", Map.of());

        String markdown = subject.renderMarkdown(document, tempDirectory);
        Path diagram = tempDirectory.resolve("smiles-0-0.png");

        assertThat(markdown)
                .contains("Aspirin description.\n\n![SMILES chemical structure](smiles-0-0.png \"chat4j-smiles-large\")")
                .contains("SMILES: `%s`\n\nFollowing prose.".formatted(smiles))
                .doesNotContain("```SMILES");
        assertThat(diagram).isRegularFile();
        var image = ImageIO.read(diagram.toFile());
        assertThat(image.getWidth()).isEqualTo(SmilesDiagramRenderer.IMAGE_WIDTH);
        assertThat(image.getHeight()).isEqualTo(SmilesDiagramRenderer.IMAGE_HEIGHT);
    }

    @Test
    @DisplayName("Publication Markdown preserves invalid and unterminated SMILES blocks as readable source")
    void renderMarkdown_whenSmilesCannotRender_preservesOriginalSource() throws Exception {
        String oversized = "C".repeat(SmilesDiagramRenderer.MAX_SOURCE_LENGTH + 1);
        var document = ConversationPdfDocument.builder()
                .title("Publication")
                .exportedAt(Instant.EPOCH)
                .turns(List.of(new ConversationPdfDocument.Turn(
                        Role.ASSISTANT,
                        Instant.EPOCH,
                        List.of(new TextPart("""
                                ```smiles
                                not a smiles
                                ```

                                ```smiles
                                %s
                                ```

                                ```smiles
                                CCO
                                """.formatted(oversized))),
                        List.of(),
                        false,
                        "",
                        List.of()
                )))
                .build();
        var subject = new PandocConversationPdfExporter("pandoc", "lualatex", Map.of());

        String markdown = subject.renderMarkdown(document, tempDirectory);

        assertThat(markdown)
                .contains("not a smiles")
                .contains("SMILES diagram could not be rendered.")
                .contains("SMILES source is too large to render.")
                .contains("```smiles\nCCO")
                .doesNotContain("smiles-0-0.png");
        try (var files = Files.list(tempDirectory)) {
            assertThat(files.filter(path -> path.getFileName().toString().startsWith("smiles-")))
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("Publication Markdown never renders SMILES markers inside another fenced block")
    void renderMarkdown_whenSmilesMarkerClosesAnotherFence_preservesLiteralSource() throws Exception {
        String literal = """
                ```markdown
                literal source
                ```smiles
                CCO
                ```
                """;
        var document = ConversationPdfDocument.builder()
                .title("Publication")
                .exportedAt(Instant.EPOCH)
                .turns(List.of(new ConversationPdfDocument.Turn(
                        Role.ASSISTANT,
                        Instant.EPOCH,
                        List.of(new TextPart(literal)),
                        List.of(),
                        false,
                        "",
                        List.of()
                )))
                .build();
        var subject = new PandocConversationPdfExporter("pandoc", "lualatex", Map.of());

        String markdown = subject.renderMarkdown(document, tempDirectory);

        assertThat(markdown).contains(literal.stripTrailing());
        try (var files = Files.list(tempDirectory)) {
            assertThat(files.filter(path -> path.getFileName().toString().startsWith("smiles-")))
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("Publication SMILES fences follow the same boundaries as transcript Markdown rendering")
    void renderMarkdown_whenFenceUsesTranscriptBoundarySemantics_matchesTranscriptRendering() throws Exception {
        var document = ConversationPdfDocument.builder()
                .title("Publication")
                .exportedAt(Instant.EPOCH)
                .turns(List.of(new ConversationPdfDocument.Turn(
                        Role.ASSISTANT,
                        Instant.EPOCH,
                        List.of(new TextPart("""
                                    ```smiles
                                CCO
                                ``` trailing text

                                ````smiles
                                c1ccccc1
                                ````
                                """)),
                        List.of(),
                        false,
                        "",
                        List.of()
                )))
                .build();
        var subject = new PandocConversationPdfExporter("pandoc", "lualatex", Map.of());

        String markdown = subject.renderMarkdown(document, tempDirectory);

        assertThat(markdown)
                .contains("![SMILES chemical structure](smiles-0-0.png \"chat4j-smiles-small\")")
                .contains("````smiles\nc1ccccc1\n````")
                .doesNotContain("smiles-0-1.png");
    }

    @Test
    @DisplayName("Publication Markdown cancellation prevents subsequent SMILES asset writes")
    void renderMarkdown_whenCancelledBetweenDiagrams_stopsWritingAssets() throws Exception {
        var document = ConversationPdfDocument.builder()
                .title("Publication")
                .exportedAt(Instant.EPOCH)
                .turns(List.of(new ConversationPdfDocument.Turn(
                        Role.ASSISTANT,
                        Instant.EPOCH,
                        List.of(new TextPart("""
                                ```smiles
                                CCO
                                ```

                                ```smiles
                                c1ccccc1
                                ```
                                """)),
                        List.of(),
                        false,
                        "",
                        List.of()
                )))
                .build();
        var subject = new PandocConversationPdfExporter("pandoc", "lualatex", Map.of());
        var checks = new AtomicInteger();

        subject.renderMarkdown(document, tempDirectory, () -> checks.incrementAndGet() >= 4);

        assertThat(tempDirectory.resolve("smiles-0-0.png")).isRegularFile();
        assertThat(tempDirectory.resolve("smiles-0-1.png")).doesNotExist();
    }

    @Test
    @DisplayName("Publication cancellation stops image staging before a managed asset is written")
    void renderMarkdown_whenCancelledDuringImageDecode_stopsBeforeAssetWrite() throws Exception {
        Path source = tempDirectory.resolve("source.png");
        assertThat(ImageIO.write(
                new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB),
                "png",
                source.toFile()
        )).isTrue();
        var reference = new AttachmentRef(
                UUID.randomUUID(),
                source.toString(),
                "source.png",
                "image/png",
                Files.size(source),
                ""
        );
        var document = ConversationPdfDocument.builder()
                .title("Publication")
                .exportedAt(Instant.EPOCH)
                .turns(List.of(new ConversationPdfDocument.Turn(
                        Role.ASSISTANT,
                        Instant.EPOCH,
                        List.of(new TextPart("Image follows"), new GeneratedImagePart(reference, 40, 30, "Image")),
                        List.of(),
                        false,
                        "",
                        List.of()
                )))
                .build();
        var subject = new PandocConversationPdfExporter("pandoc", "lualatex", Map.of());
        var checks = new AtomicInteger();

        String markdown = subject.renderMarkdown(document, tempDirectory, () -> checks.incrementAndGet() >= 4);

        assertThat(tempDirectory.resolve("image-0-0.png")).doesNotExist();
        assertThat(markdown).doesNotContain("Image unavailable", "image-0-0.png");
    }

    @Test
    @DisplayName("Publication Markdown keeps Mermaid source when the optional CLI is not configured")
    void renderMarkdown_whenMermaidCliIsBlank_preservesSourceWithNotice() throws Exception {
        var subject = new PandocConversationPdfExporter("pandoc", "lualatex", Map.of());
        var document = ConversationPdfDocument.builder()
                .title("Publication")
                .exportedAt(Instant.EPOCH)
                .turns(List.of(new ConversationPdfDocument.Turn(
                        Role.ASSISTANT,
                        Instant.EPOCH,
                        List.of(new TextPart("""
                                ```mermaid
                                flowchart LR
                                A --> B
                                ```
                                """)),
                        List.of(),
                        false,
                        "",
                        List.of()
                )))
                .build();

        String markdown = subject.renderMarkdown(document, tempDirectory);

        assertThat(markdown)
                .contains("```mermaid\nflowchart LR\nA --> B\n```")
                .contains("Mermaid renderer is unavailable; source is shown.")
                .doesNotContain("mermaid-0-0.png");
    }

    @Test
    @DisplayName("Publication Markdown replaces rendered Mermaid source with a concise managed diagram")
    void renderMarkdown_whenMermaidRenders_writesManagedDiagramWithoutDuplicatingSource() throws Exception {
        PdfExportProcessRunner processRunner = successfulMermaidRunner(1_200, 500);
        var mermaidRenderer = new MermaidCliDiagramRenderer("mmdc", Map.of(), processRunner);
        var subject = exporterWith(mermaidRenderer, processRunner);
        var document = ConversationPdfDocument.builder()
                .title("Publication")
                .exportedAt(Instant.EPOCH)
                .turns(List.of(new ConversationPdfDocument.Turn(
                        Role.ASSISTANT,
                        Instant.EPOCH,
                        List.of(new TextPart("""
                                Before diagram.
                                ```Mermaid
                                flowchart LR
                                  A --> B
                                ```
                                After diagram.
                                """)),
                        List.of(),
                        false,
                        "",
                        List.of()
                )))
                .build();

        String markdown = subject.renderMarkdown(document, tempDirectory);

        assertThat(markdown)
                .contains("Before diagram.\n\n![Mermaid diagram](mermaid-0-0.png \"chat4j-mermaid-medium\")")
                .contains("*Mermaid diagram*\n\nAfter diagram.")
                .doesNotContain("flowchart LR")
                .doesNotContain("```Mermaid");
        assertThat(tempDirectory.resolve("mermaid-0-0.png")).isRegularFile();
        assertThat(tempDirectory.resolve("mermaid-0-0.mmd")).hasContent("flowchart LR\n  A --> B");
    }

    @Test
    @DisplayName("Publication Markdown keeps exact Mermaid source when rendering is unavailable")
    void renderMarkdown_whenMermaidCannotRender_preservesSourceWithNotice() throws Exception {
        PdfExportProcessRunner processRunner = mock(PdfExportProcessRunner.class);
        var mermaidRenderer = new MermaidCliDiagramRenderer("mmdc", Map.of(), processRunner);
        var subject = exporterWith(mermaidRenderer, processRunner);
        String source = """
                ```mermaid
                flowchart LR
                  A[https://example.com/image.png]
                ```
                Following prose.
                """;
        var document = ConversationPdfDocument.builder()
                .title("Publication")
                .exportedAt(Instant.EPOCH)
                .turns(List.of(new ConversationPdfDocument.Turn(
                        Role.ASSISTANT,
                        Instant.EPOCH,
                        List.of(new TextPart(source)),
                        List.of(),
                        false,
                        "",
                        List.of()
                )))
                .build();

        String markdown = subject.renderMarkdown(document, tempDirectory);

        assertThat(markdown)
                .contains("```mermaid\nflowchart LR\n  A[https://example.com/image.png]\n```")
                .contains("```\n\n> Mermaid diagram uses external or local resources and was not rendered offline.\n\nFollowing prose.")
                .doesNotContain("mermaid-0-0.png");
        verifyNoInteractions(processRunner);
    }

    @Test
    @DisplayName("Repeated Mermaid source starts one render while staging each conversation occurrence")
    void renderMarkdown_whenMermaidRepeats_usesExportLocalCache() throws Exception {
        PdfExportProcessRunner processRunner = successfulMermaidRunner(600, 400);
        var mermaidRenderer = new MermaidCliDiagramRenderer("mmdc", Map.of(), processRunner);
        var subject = exporterWith(mermaidRenderer, processRunner);
        String repeated = """
                ```mermaid
                flowchart LR
                A --> B
                ```
                """;
        var document = ConversationPdfDocument.builder()
                .title("Publication")
                .exportedAt(Instant.EPOCH)
                .turns(List.of(
                        new ConversationPdfDocument.Turn(
                                Role.USER,
                                Instant.EPOCH,
                                List.of(new TextPart(repeated)),
                                List.of(),
                                false,
                                "",
                                List.of()
                        ),
                        new ConversationPdfDocument.Turn(
                                Role.ASSISTANT,
                                Instant.EPOCH,
                                List.of(new TextPart(repeated)),
                                List.of(),
                                false,
                                "",
                                List.of()
                        )
                ))
                .build();

        String markdown = subject.renderMarkdown(document, tempDirectory);

        assertThat(markdown)
                .contains("mermaid-0-0.png")
                .contains("mermaid-1-0.png");
        assertThat(tempDirectory.resolve("mermaid-0-0.png")).isRegularFile();
        assertThat(tempDirectory.resolve("mermaid-1-0.png")).isRegularFile();
        verify(processRunner, times(1)).run(
                anyList(),
                any(Path.class),
                anyMap(),
                any(BooleanSupplier.class),
                any(Duration.class),
                anyString()
        );
    }

    @Test
    @DisplayName("Publication command enforces A4 and applies the parsed-node safety filter")
    void command_whenBuilt_usesPublicationSafetyAndPageSettings() {
        var subject = new PandocConversationPdfExporter("pandoc", "lualatex", Map.of());
        Path output = tempDirectory.resolve("conversation.pdf");

        Path markdown = tempDirectory.resolve("conversation.md");
        List<String> command = subject.command(markdown, output, tempDirectory);
        List<String> fallbackCommand = subject.mathSourceFallbackCommand(markdown, output, tempDirectory);

        assertThat(command)
                .contains("--from=gfm+tex_math_dollars-raw_html")
                .contains("--lua-filter=%s".formatted(tempDirectory.resolve("publication-filter.lua")))
                .contains("--template=%s".formatted(tempDirectory.resolve("publication-template.tex")))
                .contains("--pdf-engine-opt=-no-shell-escape")
                .contains("--output=%s".formatted(output))
                .doesNotContain("--lua-filter=%s".formatted(
                        tempDirectory.resolve("publication-math-source-filter.lua")
                ))
                .noneMatch(argument -> argument.startsWith("--highlight-style="));
        assertThat(fallbackCommand).contains("--lua-filter=%s".formatted(
                tempDirectory.resolve("publication-math-source-filter.lua")
        ));
    }

    @Test
    @DisplayName("Configured real Mermaid CLI produces a parseable Publication PDF when smoke tools are enabled")
    void export_whenRealMermaidSmokeIsEnabled_embedsRenderedDiagram() throws Exception {
        String mermaidCli = System.getenv("CHAT4J_MERMAID_CLI_SMOKE");
        String latexExecutable = System.getenv("CHAT4J_LATEX_SMOKE");
        Assumptions.assumeTrue(mermaidCli != null && !mermaidCli.isBlank());
        Assumptions.assumeTrue(latexExecutable != null && !latexExecutable.isBlank());
        Map<String, String> environment = ChromiumExecutableResolver.withExecutable(System.getenv(), "");
        var subject = new PandocConversationPdfExporter("pandoc", latexExecutable, mermaidCli, environment);
        String tallEdges = IntStream.range(0, 60)
                .mapToObj(index -> "T%d --> T%d".formatted(index, index + 1))
                .collect(joining("\n"));
        String wideEdges = IntStream.range(0, 16)
                .mapToObj(index -> "W%d --> W%d".formatted(index, index + 1))
                .collect(joining("\n"));
        String content = """
                ## Publication math

                \\[
                \\boxed{\\mathcal{E} = -\\frac{d\\Phi_B}{dt}}
                \\]

                Using the Faraday constant \\( F \\approx 96\\,485\\ \\text{C mol}^{-1} \\)
                and the number of electrons \\( n \\), with equivalent weight \\( E = M/n \\).

                Inline \\( d\\boldsymbol{\\ell} \\).

                $$\\displaystyle \\oint_C \\mathbf{E} \\cdot d\\boldsymbol{\\ell}$$

                ```mermaid
                graph TD
                  A[Start Parsing] --> B{Identify Diagram Type};
                  B -- graph/flowchart --> C[Define Nodes (ID)];
                  C --> D[Define Connections (-->)];
                  D --> E[Render Diagram];
                ```

                ```mermaid
                sequenceDiagram
                  participant User
                  participant Exporter
                  User->>Exporter: Export
                ```

                ```mermaid
                stateDiagram-v2
                  [*] --> Ready
                  Ready --> Exported
                ```

                ```mermaid
                classDiagram
                  class Exporter {
                    +export()
                  }
                ```

                ```mermaid
                erDiagram
                  CONVERSATION ||--o{ MESSAGE : contains
                ```

                ```mermaid
                mindmap
                  root((Export))
                    PDF
                    Diagram
                ```

                ```mermaid
                flowchart TB
                %s
                ```

                ```mermaid
                flowchart LR
                %s
                ```

                ```mermaid
                not-a-diagram MALFORMED
                ```

                ```mermaid
                flowchart LR
                  PRIVATE[https://example.com/private.png]
                ```

                ```mermaid
                flowchart LR
                  A[Start] --> B{Ready?}
                  B -->|Yes| C[Export PDF]
                ```
                """.formatted(tallEdges, wideEdges);
        Path tallAttachment = tempDirectory.resolve("tall-attachment.png");
        assertThat(ImageIO.write(
                new BufferedImage(100, 17_000, BufferedImage.TYPE_INT_RGB),
                "png",
                tallAttachment.toFile()
        )).isTrue();
        var tallReference = new AttachmentRef(
                UUID.randomUUID(),
                tallAttachment.toString(),
                "tall-attachment.png",
                "image/png",
                Files.size(tallAttachment),
                ""
        );
        var document = ConversationPdfDocument.builder()
                .title("Mermaid Publication Smoke")
                .exportedAt(Instant.EPOCH)
                .turns(List.of(new ConversationPdfDocument.Turn(
                        Role.ASSISTANT,
                        Instant.EPOCH,
                        List.of(
                                new TextPart(content),
                                new GeneratedImagePart(tallReference, 100, 17_000, "Tall attachment")
                        ),
                        List.of(),
                        false,
                        "",
                        List.of()
                )))
                .build();
        Path output = tempDirectory.resolve("mermaid-publication-smoke.pdf");

        subject.export(document, output, () -> false);

        assertThat(output).isRegularFile().isNotEmptyFile();
        try (var pdf = Loader.loadPDF(output.toFile())) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(1);
            assertThat(new PDFTextStripper().getText(pdf))
                    .contains("Mermaid diagram")
                    .contains("MALFORMED", "example.com/private.png", "Using the Faraday constant")
                    .doesNotContain("$ F", "$ n", "$ E", "\\approx", "\\text{C mol}")
                    .doesNotContain(
                            "graph TD",
                            "Identify Diagram Type",
                            "sequenceDiagram",
                            "stateDiagram-v2",
                            "classDiagram",
                            "erDiagram",
                            "mindmap",
                            "\\boxed",
                            "\\boldsymbol",
                            "\\displaystyle"
                    );
        }
    }

    @Test
    @DisplayName("Publication template provides bundled typography and repeating document chrome")
    void publicationTemplate_whenPandocIsInstalled_expandsBundledLayout() throws Exception {
        Path template = tempDirectory.resolve("publication-template.tex");
        try (var input = PandocConversationPdfExporter.class.getResourceAsStream(
                "/web/export/pdf/publication-template.tex"
        )) {
            assertThat(input).isNotNull();
            Files.copy(input, template);
        }
        Path markdown = tempDirectory.resolve("template-input.md");
        Files.writeString(markdown, """
                ---
                title: Conversation
                subtitle: Provider · model
                date: 2026-08-08
                ---

                Body

                ```java
                class Example { int value = 1; }
                ```
                """, StandardCharsets.UTF_8);

        Process process;
        try {
            process = new ProcessBuilder(
                    "pandoc",
                    markdown.toString(),
                    "--from=gfm",
                    "--to=latex",
                    "--standalone",
                    "--template=%s".formatted(template)
            ).redirectErrorStream(true).start();
        } catch (IOException e) {
            Assumptions.abort("Pandoc is not installed");
            return;
        }
        String latex = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).isZero();
        assertThat(latex)
                .contains("LibertinusSerif-Regular.ttf")
                .contains("JetBrainsMono-Regular.ttf")
                .contains("JetBrainsMono-Bold.ttf")
                .contains("JetBrainsMono-Italic.ttf")
                .contains("JetBrainsMono-BoldItalic.ttf")
                .contains("NotoEmoji.ttf")
                .contains("\\newfontfamily\\chatjEmojiFont")
                .contains("Scale=MatchLowercase")
                .contains("\\usepackage{hyperref}")
                .contains("\\setlength{\\parindent}{0pt}")
                .contains("\\setlength{\\parskip}{0.7\\baselineskip plus 2pt minus 1pt}")
                .contains("\\newenvironment{Shaded}{}{}")
                .contains("\\KeywordTok{class}")
                .contains("fancyhead[C]")
                .contains("Conversation")
                .contains("Provider · model")
                .doesNotContain("\\usepackage{framed}");
    }

    @Test
    @DisplayName("Publication filter renders safe formulas while preserving unsafe TeX as source")
    void publicationFilter_whenPandocIsInstalled_appliesPolicyToParsedNodes() throws Exception {
        Path filter = tempDirectory.resolve("publication-filter.lua");
        try (var input = PandocConversationPdfExporter.class.getResourceAsStream(
                "/web/export/pdf/publication-filter.lua"
        )) {
            assertThat(input).isNotNull();
            Files.copy(input, filter);
        }
        Files.write(tempDirectory.resolve("image-0-0.png"), new byte[]{0});
        Files.write(tempDirectory.resolve("smiles-0-0.png"), new byte[]{0});
        Files.write(tempDirectory.resolve("mermaid-0-0.png"), new byte[]{0});
        String scotlandFlag = new String(new int[]{
                0x1F3F4, 0xE0067, 0xE0062, 0xE0073, 0xE0063, 0xE0074, 0xE007F
        }, 0, 7);
        Path markdown = tempDirectory.resolve("filter-input.md");
        Files.writeString(markdown, """
                [safe](https://example.com) [3](https://citation.example) [12](http://citation.example/twelve) [999](https://citation.example/999) [4](mailto:test@example.com) [2024](https://example.com/report) [unsafe](javascript:alert(1))

                💡 ✅ ⚠️ 🚀 🌟 1️⃣ 2⃣ %s

                Safe inline: $\\text{CO}_2$
                Safe bold symbol: $\\boldsymbol{\\ell}$
                Safe display style: $\\displaystyle \\oint_C \\mathbf{E} \\cdot d\\boldsymbol{\\ell}$

                $$\\boxed{\\mathcal{E} = -\\frac{d\\Phi_B}{dt}}$$

                $$\\text{Acid} + \\text{Base} \\rightarrow \\text{Salt} + \\text{CO}_2$$

                $$\\begin{array}{c} \\mathrm{H}_2\\mathrm{O} \\\\ \\mathrm{CO}_2 \\end{array}$$

                Unsafe input: $\\input{/etc/hosts}$
                Unsafe command construction: $\\csname input\\endcsname{/etc/hosts}$
                Unsafe character encoding: $^^5cinput{/etc/hosts}$
                Malformed array: $\\begin{array}x\\end{array}$
                Unmatched delimiter: $\\left x$
                Escaped array alignment: $x & y + \\begin{array}{c}\\end{array}$
                Missing fraction argument: $\\frac{1}$
                Missing superscript argument: $x^$

                ![remote](https://example.com/image.png)
                ![local](/private/image.png)
                ![managed](image-0-0.png)
                ![smiles](smiles-0-0.png "chat4j-smiles-small")
                ![mermaid](mermaid-0-0.png "chat4j-mermaid-medium")
                ![unmarked](mermaid-0-1.png)
                ![user-title](mermaid-0-2.png "user title")
                ![missing-managed](image-9-9.png)
                ![missing-mermaid](mermaid-9-9.png "chat4j-mermaid-small")

                | Left | Right |
                | --- | --- |
                | short | content that must wrap within the page |

                ```markdown
                ![literal](/code/example.png)
                [literal](javascript:example)
                ```
                """.formatted(scotlandFlag), StandardCharsets.UTF_8);

        Process process;
        try {
            process = new ProcessBuilder(
                    "pandoc",
                    markdown.toString(),
                    "--from=gfm+tex_math_dollars-raw_html",
                    "--to=json",
                    "--sandbox",
                    "--lua-filter=%s".formatted(filter)
            ).directory(tempDirectory.toFile()).redirectErrorStream(true).start();
        } catch (IOException e) {
            Assumptions.abort("Pandoc is not installed");
            return;
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).isZero();
        assertThat(output)
                .contains("https://example.com")
                .contains("https://citation.example")
                .contains("\"t\":\"Superscript\"")
                .contains("\"t\":\"Str\",\"c\":\"[3]\"")
                .contains("\"t\":\"Str\",\"c\":\"[12]\"")
                .contains("\"t\":\"Str\",\"c\":\"[999]\"")
                .contains("mailto:test@example.com")
                .contains("\"t\":\"Str\",\"c\":\"4\"")
                .contains("\"t\":\"Str\",\"c\":\"2024\"")
                .doesNotContain("\"t\":\"Str\",\"c\":\"[4]\"")
                .doesNotContain("\"t\":\"Str\",\"c\":\"[2024]\"")
                .contains("https://example.com/image.png")
                .contains("image-0-0.png")
                .contains("smiles-0-0.png")
                .contains("mermaid-0-0.png")
                .doesNotContain(
                        "mermaid-0-1.png",
                        "mermaid-0-2.png",
                        "image-9-9.png",
                        "mermaid-9-9.png"
                )
                .contains("\"t\":\"ColWidth\",\"c\":0.5")
                .contains("chatjEmojiFont")
                .contains("💡")
                .contains("✅")
                .contains("⚠️")
                .contains("🚀")
                .contains("🌟")
                .contains("1️⃣")
                .contains("2⃣")
                .contains(scotlandFlag)
                .contains("![literal](/code/example.png)")
                .contains("[literal](javascript:example)")
                .doesNotContain("/private/image.png")
                .doesNotContain("javascript:alert");
        assertThat(countOccurrences(output, "\"t\":\"Math\"")).isEqualTo(6);
        assertThat(countOccurrences(output, "\"t\":\"Superscript\"")).isEqualTo(3);

        Process latexProcess = new ProcessBuilder(
                "pandoc",
                markdown.toString(),
                "--from=gfm+tex_math_dollars-raw_html",
                "--to=latex",
                "--sandbox",
                "--lua-filter=%s".formatted(filter)
        ).directory(tempDirectory.toFile()).redirectErrorStream(true).start();
        String latex = new String(latexProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(latexProcess.waitFor()).isZero();
        assertThat(latex)
                .contains("\\(\\text{CO}_2\\)")
                .contains("\\(\\boldsymbol{\\ell}\\)")
                .contains("\\(\\displaystyle \\oint_C \\mathbf{E} \\cdot d\\boldsymbol{\\ell}\\)")
                .contains("\\[\\boxed{\\mathcal{E} = -\\frac{d\\Phi_B}{dt}}\\]")
                .contains("\\[\\text{Acid} + \\text{Base} \\rightarrow \\text{Salt} + \\text{CO}_2\\]")
                .contains("\\begin{array}{c}")
                .contains("width=0.34\\linewidth")
                .contains("width=0.62\\linewidth")
                .contains("textbackslash")
                .contains("\\chatjEmojiFont 💡")
                .contains("\\chatjEmojiFont ✅")
                .contains("\\chatjEmojiFont ⚠️")
                .contains("\\chatjEmojiFont 🚀")
                .contains("\\chatjEmojiFont 🌟")
                .contains("\\chatjEmojiFont 1️⃣")
                .contains("\\chatjEmojiFont 2⃣")
                .contains("\\chatjEmojiFont %s".formatted(scotlandFlag))
                .contains("\\real{0.5000}")
                .contains("\\textsuperscript{\\href{https://citation.example}{{[}3{]}}}")
                .doesNotContain("\\(\\input{/etc/hosts}\\)")
                .doesNotContain("\\(\\csname input\\endcsname{/etc/hosts}\\)")
                .doesNotContain("\\(^^5cinput{/etc/hosts}\\)");
    }

    @Test
    @DisplayName("Publication validation reports missing tools before export")
    void unavailableReason_whenPandocDoesNotExist_reportsActionableReason() {
        var subject = new PandocConversationPdfExporter(
                tempDirectory.resolve("missing-pandoc").toString(),
                tempDirectory.resolve("missing-lualatex").toString(),
                Map.of()
        );

        assertThat(subject.unavailableReason(() -> false))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("Pandoc", "unavailable")
                        .doesNotContain("missing-pandoc"));
    }

    private PandocConversationPdfExporter exporterWith(
            MermaidCliDiagramRenderer mermaidRenderer,
            PdfExportProcessRunner processRunner
    ) {
        return new PandocConversationPdfExporter(
                "pandoc",
                "lualatex",
                Map.of(),
                processRunner,
                mermaidRenderer
        );
    }

    private PdfExportProcessRunner successfulMermaidRunner(int width, int height) throws Exception {
        PdfExportProcessRunner processRunner = mock(PdfExportProcessRunner.class);
        when(processRunner.run(
                anyList(),
                any(Path.class),
                anyMap(),
                any(BooleanSupplier.class),
                any(Duration.class),
                anyString()
        )).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            Path output = Path.of(command.get(command.indexOf("--output") + 1));
            Files.write(output, pngBytes(width, height));
            return PdfExportProcessRunner.Outcome.completed(0, "");
        });
        return processRunner;
    }

    private byte[] pngBytes(int width, int height) throws IOException {
        var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        try (var output = new ByteArrayOutputStream()) {
            assertThat(ImageIO.write(image, "png", output)).isTrue();
            return output.toByteArray();
        }
    }

    private int countOccurrences(String text, String target) {
        return (text.length() - text.replace(target, "").length()) / target.length();
    }
}
