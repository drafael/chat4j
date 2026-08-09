package com.github.drafael.chat4j.chat.export.pdf;

import com.github.drafael.chat4j.persistence.conversation.ConversationRepository;
import com.github.drafael.chat4j.persistence.conversation.ConversationRepository.ConversationRecord;
import com.github.drafael.chat4j.persistence.conversation.ConversationRepository.LoadedConversation;
import com.github.drafael.chat4j.persistence.conversation.ConversationRepository.MessageRecord;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.MessageMeta;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationPdfExportServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("Temporary export output keeps a PDF extension for Publication format detection")
    void createTemporaryPdf_whenCreated_usesPdfExtension() throws Exception {
        Path temporary = ConversationPdfExportService.createTemporaryPdf(tempDirectory);

        assertThat(temporary.getFileName().toString()).endsWith(".pdf");
    }

    @Test
    @DisplayName("Standard export writes an A4 PDF with metadata, links, and original local images")
    void export_whenStandardModeSelected_writesRichPdf() throws Exception {
        UUID conversationId = UUID.randomUUID();
        Path imagePath = createImage(800, 500);
        ConversationRepository repository = mock(ConversationRepository.class);
        when(repository.loadConversation(conversationId)).thenReturn(Optional.of(loadedConversation(conversationId, imagePath)));
        SettingsRepository settingsRepository = new SettingsRepository(tempDirectory.resolve("settings.properties"));
        new PdfExportSettings(settingsRepository).persistMode(PdfExportMode.STANDARD);
        Path destination = tempDirectory.resolve("conversation.pdf");

        try (var subject = new ConversationPdfExportService(repository, settingsRepository, Map.of())) {
            var result = subject.export(conversationId, destination, ignored -> {
            }).completion().join();

            assertThat(result.cancelled()).isFalse();
            assertThat(result.backend()).isEqualTo("Built-in Standard");
        }

        assertThat(destination).isRegularFile().isNotEmptyFile();
        try (var pdf = Loader.loadPDF(destination.toFile())) {
            assertThat(pdf.getNumberOfPages()).isGreaterThanOrEqualTo(1);
            PDPage firstPage = pdf.getPage(0);
            assertThat(firstPage.getMediaBox().getWidth()).isCloseTo(595.28f, within(2f));
            assertThat(firstPage.getMediaBox().getHeight()).isCloseTo(841.89f, within(2f));
            assertThat(new PDFTextStripper().getText(pdf))
                    .contains("PDF Export")
                    .contains("Anthropic · claude-sonnet-5")
                    .contains("A linked answer")
                    .contains("Sources")
                    .doesNotContain("private thinking")
                    .doesNotContain("search");
            assertThat(pdf.getDocumentInformation().getTitle()).isEqualTo("PDF Export");
            assertThat(pdf.getDocumentInformation().getAuthor()).isEqualTo("Anthropic · claude-sonnet-5");
            assertThat(hasUriAnnotation(pdf.getPage(0), "https://example.com")).isTrue();
            assertThat(hasUriAnnotation(pdf.getPage(0), "javascript:alert(1)")).isFalse();
            assertThat(imageCount(pdf.getPage(0))).isGreaterThanOrEqualTo(1);
            assertThat(maxImageWidth(pdf.getPage(0))).isGreaterThanOrEqualTo(800);
            assertThat(embeddedFontNames(pdf.getPage(0))).anyMatch(name -> name.contains("Libertinus"));
        }
    }

    @Test
    @DisplayName("Auto mode uses an available active Chromium exporter")
    void export_whenAutoModeHasEnhancedExporter_usesChromiumOutput() throws Exception {
        UUID conversationId = UUID.randomUUID();
        ConversationRepository repository = mock(ConversationRepository.class);
        when(repository.loadConversation(conversationId)).thenReturn(Optional.of(loadedConversation(conversationId, null)));
        SettingsRepository settingsRepository = new SettingsRepository(tempDirectory.resolve("auto-enhanced.properties"));
        Path destination = tempDirectory.resolve("enhanced.pdf");
        ConversationPdfExporter enhanced = (document, output, cancelled) ->
                new OpenHtmlConversationPdfExporter().export(document, output, cancelled);

        try (var subject = new ConversationPdfExportService(repository, settingsRepository, Map.of())) {
            var result = subject.export(conversationId, destination, ignored -> {
            }, enhanced).completion().join();

            assertThat(result.backend()).isEqualTo("Chromium Enhanced");
        }

        assertThat(destination).isRegularFile().isNotEmptyFile();
    }

    @Test
    @DisplayName("Auto mode safely falls back when Chromium preparation fails")
    void export_whenEnhancedExporterFails_usesStandardOutput() throws Exception {
        UUID conversationId = UUID.randomUUID();
        ConversationRepository repository = mock(ConversationRepository.class);
        when(repository.loadConversation(conversationId)).thenReturn(Optional.of(loadedConversation(conversationId, null)));
        SettingsRepository settingsRepository = new SettingsRepository(tempDirectory.resolve("auto-fallback.properties"));
        Path destination = tempDirectory.resolve("fallback.pdf");
        ConversationPdfExporter enhanced = (document, output, cancelled) -> {
            throw new IOException("Chromium is not ready");
        };

        try (var subject = new ConversationPdfExportService(repository, settingsRepository, Map.of())) {
            var result = subject.export(conversationId, destination, ignored -> {
            }, enhanced).completion().join();

            assertThat(result.backend()).contains("Built-in Standard").contains("diagrams shown as source");
        }

        assertThat(destination).isRegularFile().isNotEmptyFile();
    }

    @Test
    @DisplayName("Code blocks use the monospaced font for regular, bold, and italicized syntax")
    void export_whenCodeBlockContainsStyledSyntax_usesMonospacedFontThroughout() throws Exception {
        Path destination = tempDirectory.resolve("monospaced-code.pdf");
        var document = ConversationPdfDocument.builder()
                .title("Monospaced code")
                .exportedAt(Instant.EPOCH)
                .turns(List.of(new ConversationPdfDocument.Turn(
                        Role.ASSISTANT,
                        Instant.EPOCH,
                        List.of(new TextPart("""
                                ```java
                                synchronized void regularToken() {
                                    // commentToken
                                }
                                ```
                                """)),
                        List.of(),
                        false,
                        "",
                        List.of()
                )))
                .build();

        new OpenHtmlConversationPdfExporter().export(document, destination, () -> false);

        try (var pdf = Loader.loadPDF(destination.toFile())) {
            assertThat(fontNamesForText(pdf, "synchronized")).allMatch(this::isJetBrainsMono);
            assertThat(fontNamesForText(pdf, "regularToken")).allMatch(this::isJetBrainsMono);
            assertThat(fontNamesForText(pdf, "commentToken")).allMatch(this::isJetBrainsMono);
        }
    }

    @Test
    @DisplayName("Standard export preserves emoji with an embedded fallback font")
    void export_whenTextContainsEmoji_rendersSupportedGlyphs() throws Exception {
        Path destination = tempDirectory.resolve("emoji.pdf");
        var document = ConversationPdfDocument.builder()
                .title("Emoji")
                .exportedAt(Instant.EPOCH)
                .turns(List.of(new ConversationPdfDocument.Turn(
                        Role.ASSISTANT,
                        Instant.EPOCH,
                        List.of(new TextPart("💡 ✅ ⚠️ 🚀 🌟")),
                        List.of(),
                        false,
                        "",
                        List.of()
                )))
                .build();

        new OpenHtmlConversationPdfExporter().export(document, destination, () -> false);

        try (var pdf = Loader.loadPDF(destination.toFile())) {
            assertThat(new PDFTextStripper().getText(pdf))
                    .contains("💡")
                    .contains("✅")
                    .contains("⚠")
                    .contains("🚀")
                    .contains("🌟");
            assertThat(embeddedFontNames(pdf.getPage(0))).anyMatch(name -> name.contains("NotoEmoji"));
        }
    }

    @Test
    @DisplayName("Long code blocks continue across pages without losing their final content")
    void export_whenCodeBlockSpansPages_preservesFinalLine() throws Exception {
        UUID conversationId = UUID.randomUUID();
        LoadedConversation base = loadedConversation(conversationId, null);
        String code = java.util.stream.IntStream.range(0, 220)
                .mapToObj(index -> "line-%03d: System.out.println(\"value\");".formatted(index))
                .collect(java.util.stream.Collectors.joining("\n"));
        Message message = Message.assistant("```java\n%s\nEND_MARKER\n```".formatted(code));
        LoadedConversation loaded = new LoadedConversation(
                base.conversation(),
                List.of(new MessageRecord(UUID.randomUUID(), 1, message))
        );
        ConversationRepository repository = mock(ConversationRepository.class);
        when(repository.loadConversation(conversationId)).thenReturn(Optional.of(loaded));
        SettingsRepository settingsRepository = new SettingsRepository(tempDirectory.resolve("multipage.properties"));
        Path destination = tempDirectory.resolve("multipage.pdf");

        try (var subject = new ConversationPdfExportService(repository, settingsRepository, Map.of())) {
            subject.export(conversationId, destination, ignored -> {
            }).completion().join();
        }

        try (var pdf = Loader.loadPDF(destination.toFile())) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(1);
            assertThat(new PDFTextStripper().getText(pdf)).contains("END_MARKER");
        }
    }

    @Test
    @DisplayName("Publication validation checks configured tools before a destination is chosen")
    void validatePublicationBackend_whenConfiguredPandocIsMissing_returnsReason() throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        SettingsRepository settingsRepository = new SettingsRepository(tempDirectory.resolve("publication-validation.properties"));
        PdfExportSettings settings = new PdfExportSettings(settingsRepository);
        settings.persistMode(PdfExportMode.PUBLICATION);
        settings.persistPandocPath(tempDirectory.resolve("missing-pandoc").toString());

        try (var subject = new ConversationPdfExportService(repository, settingsRepository, Map.of())) {
            assertThat(subject.validatePublicationBackend(() -> false).join())
                    .hasValueSatisfying(reason -> assertThat(reason).contains("Pandoc"));
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("Publication validation discovers Mermaid CLI from PATH when no override is configured")
    void validatePublicationBackend_whenMermaidCliIsOnPath_checksDiscoveredExecutable() throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        SettingsRepository settingsRepository = new SettingsRepository(tempDirectory.resolve("mermaid-discovery.properties"));
        PdfExportSettings settings = new PdfExportSettings(settingsRepository);
        settings.persistMode(PdfExportMode.PUBLICATION);
        settings.persistPandocPath(javaExecutable().toString());
        settings.persistLatexPath(javaExecutable().toString());
        Path mermaidCli = Files.writeString(tempDirectory.resolve("mmdc"), "#!/bin/sh\nprintf '10.9.0\\n'\n");
        assertThat(mermaidCli.toFile().setExecutable(true)).isTrue();

        try (var subject = new ConversationPdfExportService(
                repository,
                settingsRepository,
                Map.of("PATH", tempDirectory.toString())
        )) {
            assertThat(subject.validatePublicationBackend(() -> false).join())
                    .hasValueSatisfying(reason -> assertThat(reason)
                            .contains("Mermaid CLI version 11.x", "10.x"));
        }
    }

    @Test
    @DisplayName("Publication validation checks an explicitly configured Mermaid CLI after required tools")
    void validatePublicationBackend_whenMermaidCliVersionIsUnsupported_returnsReason() {
        ConversationRepository repository = mock(ConversationRepository.class);
        SettingsRepository settingsRepository = new SettingsRepository(tempDirectory.resolve("mermaid-validation.properties"));
        PdfExportSettings settings = new PdfExportSettings(settingsRepository);
        settings.persistMode(PdfExportMode.PUBLICATION);
        settings.persistPandocPath(javaExecutable().toString());
        settings.persistLatexPath(javaExecutable().toString());
        settings.persistMermaidCliPath(javaExecutable().toString());

        try (var subject = new ConversationPdfExportService(repository, settingsRepository, Map.of())) {
            assertThat(subject.validatePublicationBackend(() -> false).join())
                    .hasValueSatisfying(reason -> assertThat(reason).contains("Mermaid CLI").contains("11.x"));
        }
    }

    @Test
    @DisplayName("A missing conversation failure preserves an existing destination")
    void export_whenConversationWasDeleted_preservesExistingDestination() throws Exception {
        UUID conversationId = UUID.randomUUID();
        ConversationRepository repository = mock(ConversationRepository.class);
        when(repository.loadConversation(conversationId)).thenReturn(Optional.empty());
        SettingsRepository settingsRepository = new SettingsRepository(tempDirectory.resolve("missing.properties"));
        Path destination = tempDirectory.resolve("preserved.pdf");
        Files.writeString(destination, "existing");

        try (var subject = new ConversationPdfExportService(repository, settingsRepository, Map.of())) {
            assertThatThrownBy(() -> subject.export(conversationId, destination, ignored -> {
            }).completion().join()).hasRootCauseMessage("The conversation no longer exists.");
        }

        assertThat(destination).hasContent("existing");
    }

    @Test
    @DisplayName("Cancelled export leaves an existing destination unchanged")
    void export_whenCancelled_doesNotReplaceDestination() throws Exception {
        UUID conversationId = UUID.randomUUID();
        ConversationRepository repository = mock(ConversationRepository.class);
        var loadStarted = new CountDownLatch(1);
        var releaseLoad = new CountDownLatch(1);
        when(repository.loadConversation(conversationId)).thenAnswer(ignored -> {
            loadStarted.countDown();
            releaseLoad.await();
            return Optional.of(loadedConversation(conversationId, null));
        });
        SettingsRepository settingsRepository = new SettingsRepository(tempDirectory.resolve("cancel-settings.properties"));
        Path destination = tempDirectory.resolve("existing.pdf");
        Files.writeString(destination, "existing");

        try (var subject = new ConversationPdfExportService(repository, settingsRepository, Map.of())) {
            var export = subject.export(conversationId, destination, ignored -> {
            });
            assertThat(loadStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(export.cancel()).isTrue();
            releaseLoad.countDown();

            assertThat(export.completion().join().cancelled()).isTrue();
        } finally {
            releaseLoad.countDown();
        }

        assertThat(destination).hasContent("existing");
    }

    private LoadedConversation loadedConversation(UUID conversationId, Path imagePath) {
        ConversationRecord conversation = new ConversationRecord(
                conversationId,
                "PDF Export",
                "Anthropic",
                "claude-sonnet-5",
                false,
                "off",
                false,
                null,
                LocalDateTime.of(2026, 8, 8, 9, 0),
                LocalDateTime.of(2026, 8, 8, 10, 0)
        );
        CitationRef citation = CitationRef.builder()
                .number(1)
                .kind(CitationKind.WEB)
                .title("Example")
                .url("https://example.com")
                .build();
        MessageMeta meta = new MessageMeta(List.of(), List.of(), false, "", "private thinking", "search", List.of(), List.of(citation));
        List<com.github.drafael.chat4j.provider.api.content.ContentPart> parts = new java.util.ArrayList<>();
        parts.add(new TextPart("A linked answer [Example](https://example.com). [unsafe](javascript:alert(1))"));
        if (imagePath != null) {
            AttachmentRef reference = new AttachmentRef(
                    UUID.randomUUID(),
                    imagePath.toString(),
                    "diagram.png",
                    "image/png",
                    imagePath.toFile().length(),
                    ""
            );
            parts.add(new GeneratedImagePart(reference, 800, 500, "High quality diagram"));
        }
        Message assistant = new Message(Role.ASSISTANT, parts, Instant.parse("2026-08-08T10:00:00Z"), meta);
        return new LoadedConversation(conversation, List.of(new MessageRecord(UUID.randomUUID(), 1, assistant)));
    }

    private Path javaExecutable() {
        Path executable = Path.of(System.getProperty("java.home"), "bin", "java");
        return Files.isExecutable(executable) ? executable : executable.resolveSibling("java.exe");
    }

    private Path createImage(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(40, 40, width - 80, height - 80);
        } finally {
            graphics.dispose();
        }
        Path path = tempDirectory.resolve("diagram.png");
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    private boolean hasUriAnnotation(PDPage page, String uri) throws Exception {
        return page.getAnnotations().stream()
                .filter(PDAnnotationLink.class::isInstance)
                .map(PDAnnotationLink.class::cast)
                .map(PDAnnotationLink::getAction)
                .filter(PDActionURI.class::isInstance)
                .map(PDActionURI.class::cast)
                .map(PDActionURI::getURI)
                .anyMatch(uri::equals);
    }

    private long imageCount(PDPage page) throws Exception {
        PDResources resources = page.getResources();
        long count = 0;
        for (var name : resources.getXObjectNames()) {
            if (resources.getXObject(name) instanceof PDImageXObject) {
                count++;
            }
        }
        return count;
    }

    private List<String> embeddedFontNames(PDPage page) throws Exception {
        List<String> names = new java.util.ArrayList<>();
        for (var name : page.getResources().getFontNames()) {
            names.add(page.getResources().getFont(name).getName());
        }
        return names;
    }

    private List<String> fontNamesForText(PDDocument document, String target) throws Exception {
        StringBuilder text = new StringBuilder();
        List<String> fontNames = new java.util.ArrayList<>();
        var stripper = new PDFTextStripper() {
            @Override
            protected void processTextPosition(TextPosition position) {
                String unicode = position.getUnicode();
                unicode.codePoints().forEach(ignored -> {
                    text.appendCodePoint(ignored);
                    fontNames.add(position.getFont().getName());
                });
                super.processTextPosition(position);
            }
        };
        stripper.getText(document);
        int start = text.indexOf(target);
        if (start < 0) {
            throw new AssertionError("Expected PDF text to contain '%s' but was '%s'".formatted(target, text));
        }
        return fontNames.subList(start, start + target.codePointCount(0, target.length()));
    }

    private boolean isJetBrainsMono(String fontName) {
        return fontName.contains("JetBrainsMono");
    }

    private int maxImageWidth(PDPage page) throws Exception {
        PDResources resources = page.getResources();
        int width = 0;
        for (var name : resources.getXObjectNames()) {
            if (resources.getXObject(name) instanceof PDImageXObject image) {
                width = Math.max(width, image.getWidth());
            }
        }
        return width;
    }

    private org.assertj.core.data.Offset<Float> within(float value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
