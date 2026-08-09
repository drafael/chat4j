package com.github.drafael.chat4j.chat.export.pdf;

import com.github.drafael.chat4j.chat.content.ExternalLinkSupport;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;

import static java.util.stream.Collectors.joining;

public final class PandocConversationPdfExporter implements ConversationPdfExporter {

    static final int MAX_LATEX_IMAGE_DIMENSION = 16_000;
    private static final Duration TOOL_CHECK_TIMEOUT = Duration.ofSeconds(10);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM);
    private static final String PUBLICATION_FILTER = "/web/export/pdf/publication-filter.lua";
    private static final String PUBLICATION_MATH_SOURCE_FILTER = "/web/export/pdf/publication-math-source-filter.lua";
    private static final String PUBLICATION_TEMPLATE = "/web/export/pdf/publication-template.tex";
    private static final String FONT_ROOT = "/web/export/pdf/fonts/";
    private static final List<String> PUBLICATION_FONTS = List.of(
            "LibertinusSerif-Regular.ttf",
            "LibertinusSerif-Bold.ttf",
            "LibertinusSerif-Italic.ttf",
            "LibertinusSerif-BoldItalic.ttf",
            "LibertinusSans-Regular.ttf",
            "LibertinusSans-Bold.ttf",
            "NotoEmoji.ttf",
            "JetBrainsMono-Regular.ttf",
            "JetBrainsMono-Bold.ttf",
            "JetBrainsMono-Italic.ttf",
            "JetBrainsMono-BoldItalic.ttf"
    );

    private final String pandocExecutable;
    private final String latexExecutable;
    private final Map<String, String> environment;
    private final PdfExportProcessRunner processRunner;
    private final MermaidCliDiagramRenderer mermaidRenderer;

    PandocConversationPdfExporter(String pandocExecutable, String latexExecutable, @NonNull Map<String, String> environment) {
        this(pandocExecutable, latexExecutable, "", environment);
    }

    public PandocConversationPdfExporter(
            String pandocExecutable,
            String latexExecutable,
            String mermaidCliExecutable,
            @NonNull Map<String, String> environment
    ) {
        this.pandocExecutable = StringUtils.defaultIfBlank(pandocExecutable, "pandoc");
        this.latexExecutable = StringUtils.defaultIfBlank(latexExecutable, "lualatex");
        this.environment = PdfExportProcessEnvironment.forPublication(environment);
        this.processRunner = new PdfExportProcessRunner();
        this.mermaidRenderer = new MermaidCliDiagramRenderer(mermaidCliExecutable, environment, processRunner);
    }

    PandocConversationPdfExporter(
            String pandocExecutable,
            String latexExecutable,
            @NonNull Map<String, String> environment,
            @NonNull PdfExportProcessRunner processRunner,
            @NonNull MermaidCliDiagramRenderer mermaidRenderer
    ) {
        this.pandocExecutable = StringUtils.defaultIfBlank(pandocExecutable, "pandoc");
        this.latexExecutable = StringUtils.defaultIfBlank(latexExecutable, "lualatex");
        this.environment = PdfExportProcessEnvironment.forPublication(environment);
        this.processRunner = processRunner;
        this.mermaidRenderer = mermaidRenderer;
    }

    public Optional<String> unavailableReason(@NonNull BooleanSupplier cancelled) {
        Optional<String> pandocFailure = executableFailure(pandocExecutable, "Pandoc", cancelled);
        if (pandocFailure.isPresent() || cancelled.getAsBoolean()) {
            return pandocFailure;
        }
        Optional<String> latexFailure = executableFailure(latexExecutable, "LaTeX engine", cancelled);
        if (latexFailure.isPresent() || cancelled.getAsBoolean()) {
            return latexFailure;
        }
        return mermaidRenderer.unavailableReason(cancelled);
    }

    private Optional<String> executableFailure(String executable, String label, BooleanSupplier cancelled) {
        try {
            PdfExportProcessRunner.Outcome outcome = processRunner.run(
                    List.of(executable, "--version"),
                    Path.of("").toAbsolutePath(),
                    environment,
                    cancelled,
                    TOOL_CHECK_TIMEOUT,
                    "chat4j-pdf-tool-check"
            );
            if (outcome.status() == PdfExportProcessRunner.Status.CANCELLED) {
                return Optional.empty();
            }
            if (outcome.status() == PdfExportProcessRunner.Status.TIMED_OUT) {
                return Optional.of("%s did not respond to --version.".formatted(label));
            }
            return outcome.completedSuccessfully()
                    ? Optional.empty()
                    : Optional.of("%s is unavailable or failed its version check.".formatted(label));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("%s validation was interrupted.".formatted(label));
        } catch (IOException e) {
            return Optional.of("%s is unavailable or could not be started.".formatted(label));
        }
    }

    @Override
    public void export(
            @NonNull ConversationPdfDocument document,
            @NonNull Path destination,
            @NonNull PdfPageFormat pageFormat,
            @NonNull BooleanSupplier cancelled
    ) throws Exception {
        Path workspace = Files.createTempDirectory("chat4j-pdf-publication-");
        try {
            Path markdown = workspace.resolve("conversation.md");
            String markdownContent = renderMarkdown(document, workspace, cancelled);
            if (cancelled.getAsBoolean()) {
                return;
            }
            Files.writeString(markdown, markdownContent, StandardCharsets.UTF_8);
            preparePublicationResources(workspace);
            if (cancelled.getAsBoolean()) {
                return;
            }
            try {
                runProcess(command(markdown, destination, workspace, pageFormat), workspace, cancelled);
            } catch (PublicationRenderException firstFailure) {
                if (cancelled.getAsBoolean()) {
                    return;
                }
                Files.deleteIfExists(destination);
                try {
                    runProcess(
                            mathSourceFallbackCommand(markdown, destination, workspace, pageFormat),
                            workspace,
                            cancelled
                    );
                } catch (Exception fallbackFailure) {
                    fallbackFailure.addSuppressed(firstFailure);
                    throw fallbackFailure;
                }
            }
        } finally {
            deleteRecursively(workspace);
        }
    }

    List<String> command(Path markdown, Path destination, Path workspace) {
        return command(markdown, destination, workspace, PdfPageFormat.A4);
    }

    List<String> command(Path markdown, Path destination, Path workspace, PdfPageFormat pageFormat) {
        return command(markdown, destination, workspace, pageFormat, false);
    }

    List<String> mathSourceFallbackCommand(Path markdown, Path destination, Path workspace) {
        return mathSourceFallbackCommand(markdown, destination, workspace, PdfPageFormat.A4);
    }

    private List<String> mathSourceFallbackCommand(
            Path markdown,
            Path destination,
            Path workspace,
            PdfPageFormat pageFormat
    ) {
        return command(markdown, destination, workspace, pageFormat, true);
    }

    private List<String> command(
            Path markdown,
            Path destination,
            Path workspace,
            PdfPageFormat pageFormat,
            boolean mathSourceFallback
    ) {
        List<String> command = new ArrayList<>(List.of(
                pandocExecutable,
                markdown.toString(),
                "--from=gfm+tex_math_dollars-raw_html",
                "--standalone",
                "--sandbox",
                "--lua-filter=%s".formatted(workspace.resolve("publication-filter.lua"))
        ));
        if (mathSourceFallback) {
            command.add("--lua-filter=%s".formatted(workspace.resolve("publication-math-source-filter.lua")));
        }
        command.addAll(List.of(
                "--template=%s".formatted(workspace.resolve("publication-template.tex")),
                "--resource-path=%s".formatted(workspace),
                "--pdf-engine=%s".formatted(latexExecutable),
                "--pdf-engine-opt=-no-shell-escape",
                "--variable=chat4jpaper=%s".formatted(pageFormat.latexPaperOption()),
                "--variable=colorlinks=true",
                "--output=%s".formatted(destination)
        ));
        return List.copyOf(command);
    }

    String renderMarkdown(ConversationPdfDocument document, Path workspace) throws IOException, InterruptedException {
        return renderMarkdown(document, workspace, () -> false);
    }

    String renderMarkdown(
            ConversationPdfDocument document,
            Path workspace,
            BooleanSupplier cancelled
    ) throws IOException, InterruptedException {
        StringBuilder markdown = new StringBuilder();
        Map<String, SmilesDiagramRenderer.Result> smilesCache = new HashMap<>();
        Map<String, MermaidCliDiagramRenderer.Result> mermaidCache = new HashMap<>();
        markdown.append("---\n");
        markdown.append("title: \"").append(yaml(document.title())).append("\"\n");
        String providerModel = List.of(document.provider(), document.model()).stream()
                .filter(StringUtils::isNotBlank)
                .collect(joining(" · "));
        if (StringUtils.isNotBlank(providerModel)) {
            markdown.append("subtitle: \"").append(yaml(providerModel)).append("\"\n");
        }
        String exportedAt = DATE_FORMATTER.format(document.exportedAt().atZone(ZoneId.systemDefault()));
        markdown.append("date: \"").append(yaml(exportedAt)).append("\"\n");
        markdown.append("---\n\n");
        if (document.createdAt() != null) {
            markdown.append("**Created:** ").append(DATE_FORMATTER.format(document.createdAt())).append("  \n");
        }
        markdown.append("**Messages:** ").append(document.turns().size()).append("\n\n");
        for (int turnIndex = 0; turnIndex < document.turns().size(); turnIndex++) {
            if (cancelled.getAsBoolean()) {
                return markdown.toString();
            }
            ConversationPdfDocument.Turn turn = document.turns().get(turnIndex);
            markdown.append("## ").append(turn.role() == Role.USER ? "User" : "Assistant").append("\n\n");
            if (turn.timestamp() != null) {
                String timestamp = DATE_FORMATTER.format(turn.timestamp().atZone(ZoneId.systemDefault()));
                markdown.append("*").append(timestamp).append("*\n\n");
            }
            String text = turn.textForRendering();
            DiagramMarkdown renderedText = renderDiagramBlocks(
                    text,
                    workspace,
                    turnIndex,
                    smilesCache,
                    mermaidCache,
                    cancelled
            );
            if (renderedText.cancelled()) {
                return markdown.toString();
            }
            markdown.append(renderedText.markdown()).append("\n\n");
            turn.fallbackNotices().stream()
                    .filter(StringUtils::isNotBlank)
                    .forEach(notice -> markdown.append("> Fallback: ")
                            .append(markdownLabel(notice))
                            .append("\n\n"));
            if (turn.cancelled()) {
                markdown.append("> Response cancelled.\n\n");
            }
            if (StringUtils.isNotBlank(turn.error()) && !text.contains(turn.error())) {
                markdown.append("> Error: ").append(markdownLabel(turn.error())).append("\n\n");
            }
            if (appendMedia(markdown, turn.parts(), workspace, turnIndex, cancelled)) {
                return markdown.toString();
            }
            appendFiles(markdown, turn.parts());
            appendCitations(markdown, turn.citations());
            markdown.append("\n---\n\n");
        }
        return markdown.toString();
    }

    private DiagramMarkdown renderDiagramBlocks(
            String text,
            Path workspace,
            int turnIndex,
            Map<String, SmilesDiagramRenderer.Result> smilesCache,
            Map<String, MermaidCliDiagramRenderer.Result> mermaidCache,
            BooleanSupplier cancelled
    ) throws IOException, InterruptedException {
        String sourceText = StringUtils.defaultString(text);
        String[] lines = sourceText.split("\\n", -1);
        StringBuilder rendered = new StringBuilder(sourceText.length());
        int smilesDiagramIndex = 0;
        int mermaidDiagramIndex = 0;

        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            String language = fenceLanguage(line);
            boolean targetFence = "smiles".equalsIgnoreCase(language) || "mermaid".equalsIgnoreCase(language);
            if (!targetFence) {
                if (!isFence(line)) {
                    appendLine(rendered, line, lineIndex < lines.length - 1);
                    continue;
                }
                int closingIndex = closingFenceIndex(lines, lineIndex + 1);
                if (closingIndex < 0) {
                    appendRemainingLines(rendered, lines, lineIndex);
                    break;
                }
                appendOriginalFence(rendered, lines, lineIndex, closingIndex);
                if (closingIndex < lines.length - 1) {
                    rendered.append('\n');
                }
                lineIndex = closingIndex;
                continue;
            }

            int closingIndex = closingFenceIndex(lines, lineIndex + 1);
            if (closingIndex < 0) {
                appendRemainingLines(rendered, lines, lineIndex);
                break;
            }
            if (cancelled.getAsBoolean()) {
                return new DiagramMarkdown(sourceText, true);
            }

            String fenceBody = String.join("\n", List.of(lines).subList(lineIndex + 1, closingIndex));
            String source = "smiles".equalsIgnoreCase(language) ? fenceBody.trim() : fenceBody;
            if ("smiles".equalsIgnoreCase(language)) {
                SmilesDiagramRenderer.Result result = smilesCache.computeIfAbsent(
                        source,
                        SmilesDiagramRenderer.instance()::render
                );
                if (cancelled.getAsBoolean()) {
                    return new DiagramMarkdown(sourceText, true);
                }
                if (result.successful()) {
                    String filename = "smiles-%d-%d.png".formatted(turnIndex, smilesDiagramIndex++);
                    Files.write(workspace.resolve(filename), result.png());
                    appendBlockBoundary(rendered);
                    String displaySize = result.displaySize().name().toLowerCase(Locale.ROOT);
                    rendered.append("![SMILES chemical structure](").append(filename)
                            .append(" \"chat4j-smiles-").append(displaySize).append("\")\n\n")
                            .append("SMILES: ").append(markdownCodeSpan(source)).append("\n\n");
                } else {
                    appendOriginalFence(rendered, lines, lineIndex, closingIndex);
                    rendered.append("\n\n> ").append(smilesFailureMessage(result.failure())).append("\n\n");
                }
            } else {
                int currentDiagramIndex = mermaidDiagramIndex++;
                MermaidCliDiagramRenderer.Result result = mermaidCache.get(source);
                if (result == null) {
                    result = mermaidRenderer.render(source, workspace, turnIndex, currentDiagramIndex, cancelled);
                    if (!result.cancelled()) {
                        mermaidCache.put(source, result);
                    }
                }
                if (result.cancelled() || cancelled.getAsBoolean()) {
                    return new DiagramMarkdown(sourceText, true);
                }
                if (result.successful()) {
                    String filename = "mermaid-%d-%d.png".formatted(turnIndex, currentDiagramIndex);
                    Files.write(workspace.resolve(filename), result.png());
                    appendBlockBoundary(rendered);
                    String displaySize = result.displaySize().name().toLowerCase(Locale.ROOT);
                    rendered.append("![Mermaid diagram](").append(filename)
                            .append(" \"chat4j-mermaid-").append(displaySize).append("\")\n\n")
                            .append("*Mermaid diagram*\n\n");
                } else {
                    appendOriginalFence(rendered, lines, lineIndex, closingIndex);
                    rendered.append("\n\n> ").append(mermaidFailureMessage(result.failure())).append("\n\n");
                }
            }
            lineIndex = closingIndex;
        }
        return new DiagramMarkdown(PublicationMathDelimiterNormalizer.normalize(rendered.toString()), false);
    }

    // Keep fence recognition aligned with MarkdownBlockRenderer, including its trimmed startsWith semantics.
    private String fenceLanguage(String line) {
        String trimmed = StringUtils.trimToEmpty(line);
        return isFence(trimmed) ? trimmed.substring(3).trim() : "";
    }

    private boolean isFence(String line) {
        return StringUtils.trimToEmpty(line).startsWith("```");
    }

    private int closingFenceIndex(String[] lines, int startIndex) {
        for (int index = startIndex; index < lines.length; index++) {
            if (lines[index].trim().startsWith("```")) {
                return index;
            }
        }
        return -1;
    }

    private void appendOriginalFence(StringBuilder output, String[] lines, int openingIndex, int closingIndex) {
        for (int index = openingIndex; index <= closingIndex; index++) {
            appendLine(output, lines[index], index < closingIndex);
        }
    }

    private void appendRemainingLines(StringBuilder output, String[] lines, int startIndex) {
        for (int index = startIndex; index < lines.length; index++) {
            appendLine(output, lines[index], index < lines.length - 1);
        }
    }

    private void appendLine(StringBuilder output, String line, boolean newline) {
        output.append(line);
        if (newline) {
            output.append('\n');
        }
    }

    private void appendBlockBoundary(StringBuilder output) {
        int length = output.length();
        boolean endsWithBlankLine = length > 1
                && output.charAt(length - 1) == '\n'
                && output.charAt(length - 2) == '\n';
        if (length == 0 || endsWithBlankLine) {
            return;
        }
        if (output.charAt(length - 1) != '\n') {
            output.append('\n');
        }
        output.append('\n');
    }

    private String markdownCodeSpan(String source) {
        int longestRun = 0;
        int currentRun = 0;
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == '`') {
                currentRun++;
                longestRun = Math.max(longestRun, currentRun);
            } else {
                currentRun = 0;
            }
        }
        String delimiter = "`".repeat(longestRun + 1);
        return "%s%s%s".formatted(delimiter, source.replaceAll("\\R", " "), delimiter);
    }

    private String smilesFailureMessage(SmilesDiagramRenderer.Failure failure) {
        return switch (failure) {
            case BLANK -> "SMILES source is blank and could not be rendered.";
            case TOO_LARGE -> "SMILES source is too large to render.";
            case INVALID -> "SMILES diagram could not be rendered.";
            case UNAVAILABLE -> "SMILES renderer is unavailable; source is shown instead.";
        };
    }

    private String mermaidFailureMessage(MermaidCliDiagramRenderer.Failure failure) {
        return switch (failure) {
            case BLANK -> "Mermaid source is blank and could not be rendered.";
            case TOO_LARGE -> "Mermaid source is too large to render.";
            case RESOURCE_REFERENCE -> "Mermaid diagram uses external or local resources and was not rendered offline.";
            case TIMEOUT -> "Mermaid diagram rendering timed out; source is shown.";
            case INVALID -> "Mermaid diagram could not be rendered; source is shown.";
            case UNAVAILABLE -> "Mermaid renderer is unavailable; source is shown.";
        };
    }

    private boolean appendMedia(
            StringBuilder markdown,
            List<ContentPart> parts,
            Path workspace,
            int turnIndex,
            BooleanSupplier cancelled
    ) throws IOException {
        List<ContentPart> images = parts.stream()
                .filter(part -> part instanceof ImagePart || part instanceof GeneratedImagePart)
                .toList();
        for (int imageIndex = 0; imageIndex < images.size(); imageIndex++) {
            if (cancelled.getAsBoolean()) {
                return true;
            }
            ContentPart part = images.get(imageIndex);
            AttachmentRef reference = part instanceof GeneratedImagePart generated
                    ? generated.attachmentRef()
                    : ((ImagePart) part).attachmentRef();
            String alt = part instanceof GeneratedImagePart generated
                    ? generated.altText()
                    : StringUtils.defaultIfBlank(reference.originalName(), "Attached image");
            Path asset = copyImage(reference, workspace, turnIndex, imageIndex, cancelled);
            if (cancelled.getAsBoolean()) {
                return true;
            }
            if (asset == null) {
                markdown.append("> Image unavailable: ").append(markdownLabel(alt)).append("\n\n");
                continue;
            }
            markdown.append("![").append(markdownLabel(alt)).append("](")
                    .append(asset.getFileName()).append(")\n\n");
        }
        return false;
    }

    private Path copyImage(
            AttachmentRef reference,
            Path workspace,
            int turnIndex,
            int imageIndex,
            BooleanSupplier cancelled
    ) throws IOException {
        if (cancelled.getAsBoolean() || reference == null || StringUtils.isBlank(reference.storagePath())) {
            return null;
        }
        Path source = Path.of(reference.storagePath());
        if (!Files.isRegularFile(source)) {
            return null;
        }
        BufferedImage image;
        try {
            image = ImageIO.read(source.toFile());
        } catch (IOException e) {
            return null;
        }
        if (image == null || cancelled.getAsBoolean()) {
            return null;
        }

        boolean requiresResize = image.getWidth() > MAX_LATEX_IMAGE_DIMENSION
                || image.getHeight() > MAX_LATEX_IMAGE_DIMENSION;
        PdfExportImageFormat imageFormat = PdfExportImageFormat.detect(source).orElse(null);
        if (cancelled.getAsBoolean()) {
            return null;
        }
        if (!requiresResize && imageFormat != null && imageFormat.isPublicationPassthrough()) {
            Path target = workspace.resolve(
                    "image-%d-%d%s".formatted(turnIndex, imageIndex, imageFormat.getExtension())
            );
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }
        Path target = workspace.resolve("image-%d-%d.png".formatted(turnIndex, imageIndex));
        BufferedImage publicationImage = requiresResize ? scaleToPublicationBounds(image) : image;
        return ImageIO.write(publicationImage, "png", target.toFile()) ? target : null;
    }

    private BufferedImage scaleToPublicationBounds(BufferedImage source) {
        double scale = Math.min(
                (double) MAX_LATEX_IMAGE_DIMENSION / source.getWidth(),
                (double) MAX_LATEX_IMAGE_DIMENSION / source.getHeight()
        );
        int width = Math.max(1, (int) Math.floor(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.floor(source.getHeight() * scale));
        int imageType = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        var scaled = new BufferedImage(width, height, imageType);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private void appendFiles(StringBuilder markdown, List<ContentPart> parts) {
        List<FilePart> files = parts.stream().filter(FilePart.class::isInstance).map(FilePart.class::cast).toList();
        if (files.isEmpty()) {
            return;
        }
        markdown.append("**Attachments**\n\n");
        files.stream()
                .map(FilePart::attachmentRef)
                .forEach(reference -> markdown.append("- ")
                        .append(markdownLabel(StringUtils.defaultIfBlank(reference.originalName(), "Attachment")))
                        .append(reference.sizeBytes() > 0 ? " (%d bytes)".formatted(reference.sizeBytes()) : "")
                        .append("\n"));
        markdown.append("\n");
    }

    private void appendCitations(StringBuilder markdown, List<CitationRef> citations) {
        if (citations == null || citations.isEmpty()) {
            return;
        }
        markdown.append("**Sources**\n\n");
        citations.forEach(citation -> {
            String title = markdownLabel(citation.displayTitle());
            markdown.append(citation.number()).append(". ");
            if (safeHttpUri(citation.url())) {
                markdown.append("[").append(title).append("](").append(citation.url().trim()).append(")");
            } else {
                markdown.append(title);
            }
            if (StringUtils.isNotBlank(citation.locationLabel())) {
                markdown.append(" — ").append(markdownLabel(citation.locationLabel()));
            }
            markdown.append("\n");
            if (StringUtils.isNotBlank(citation.citedText())) {
                markdown.append("    > ")
                        .append(markdownLabel(citation.citedText()).replace("\n", " "))
                        .append("\n");
            }
        });
        markdown.append("\n");
    }

    private void runProcess(List<String> command, Path workspace, BooleanSupplier cancelled) throws Exception {
        Map<String, String> pandocEnvironment = new HashMap<>(environment);
        pandocEnvironment.put("openin_any", "p");
        pandocEnvironment.put("openout_any", "p");
        PdfExportProcessRunner.Outcome outcome = processRunner.run(
                command,
                workspace,
                pandocEnvironment,
                cancelled
        );
        if (outcome.status() == PdfExportProcessRunner.Status.CANCELLED) {
            return;
        }
        if (!outcome.completedSuccessfully()) {
            throw new PublicationRenderException(
                    "Pandoc PDF export failed (exit %d): %s".formatted(outcome.exitCode(), outcome.diagnostics())
            );
        }
    }

    private boolean safeHttpUri(String value) {
        if (!ExternalLinkSupport.isAllowedExternalLink(value)) {
            return false;
        }
        String normalized = StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }

    private void preparePublicationResources(Path workspace) throws IOException {
        copyRequiredResource(PUBLICATION_FILTER, workspace.resolve("publication-filter.lua"));
        copyRequiredResource(
                PUBLICATION_MATH_SOURCE_FILTER,
                workspace.resolve("publication-math-source-filter.lua")
        );
        copyRequiredResource(PUBLICATION_TEMPLATE, workspace.resolve("publication-template.tex"));
        Path fontsDirectory = Files.createDirectories(workspace.resolve("fonts"));
        for (String font : PUBLICATION_FONTS) {
            copyRequiredResource("%s%s".formatted(FONT_ROOT, font), fontsDirectory.resolve(font));
        }
    }

    private void copyRequiredResource(String resourceName, Path destination) throws IOException {
        try (InputStream input = PandocConversationPdfExporter.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Missing Publication export resource: %s".formatted(resourceName));
            }
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String markdownLabel(String value) {
        return StringUtils.defaultString(value)
                .replaceAll("\\R", " ")
                .replace("\\", "\\\\")
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("`", "\\`")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[")
                .replace("]", "\\]");
    }

    private String yaml(String value) {
        return StringEscapeUtils.escapeJson(StringUtils.defaultString(value));
    }

    private record DiagramMarkdown(String markdown, boolean cancelled) {
    }

    private static final class PublicationRenderException extends IOException {

        private PublicationRenderException(String message) {
            super(message);
        }
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    path.toFile().deleteOnExit();
                }
            });
        } catch (IOException ignored) {
            root.toFile().deleteOnExit();
        }
    }
}
