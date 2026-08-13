package com.github.drafael.chat4j.chat.export.pdf;

import com.github.drafael.chat4j.chat.content.ExternalLinkSupport;
import com.github.drafael.chat4j.chat.content.MessageHtmlRenderer;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptEntryRenderer;
import com.github.drafael.chat4j.chat.render.CodeFontResolver;
import com.github.drafael.chat4j.chat.render.Palette;
import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import static java.util.stream.Collectors.joining;

public final class ConversationPrintHtmlRenderer {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM);
    private static final String PRINT_CSS = requiredResourceText("/web/export/pdf/conversation-print.css");
    private static final int PRINT_CODE_FONT_SIZE = 12;
    private static final Pattern NUMERIC_CITATION_LABEL = Pattern.compile("^\\[?([1-9]\\d{0,2})\\]?$");
    private static final Palette PRINT_PALETTE = new Palette(
            "'Libertinus Serif', serif",
            "Libertinus Serif",
            "'JetBrains Mono', monospace",
            "JetBrains Mono",
            "#202124",
            "#596273",
            "#075f9f",
            "#ffffff",
            "#d7dbe2",
            "#f5f7fa",
            "#b9c1cc",
            "#e5e9ef",
            "#eef1f5",
            "#202124",
            "#4d5665"
    );
    private final MessageHtmlRenderer messageHtmlRenderer = new MessageHtmlRenderer();

    String render(@NonNull ConversationPdfDocument exportDocument) {
        return render(exportDocument, () -> false);
    }

    String render(
            @NonNull ConversationPdfDocument exportDocument,
            @NonNull BooleanSupplier cancelled
    ) {
        String title = escape(exportDocument.title());
        String printCss = PRINT_CSS.replace("Chat4J Conversation", cssString(exportDocument.title()));
        String metadata = renderMetadata(exportDocument);
        Map<String, SmilesDiagramRenderer.Result> smilesCache = new HashMap<>();
        String turns = exportDocument.turns().stream()
                .takeWhile(turn -> !cancelled.getAsBoolean())
                .map(turn -> renderTurn(turn, smilesCache, cancelled))
                .collect(joining("\n"));
        return """
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                  <meta charset="UTF-8" />
                  <title>%s</title>
                  <style>%s</style>
                </head>
                <body>
                  <header class="document-header">
                    <h1 class="document-title">%s</h1>
                    %s
                  </header>
                  <main>%s</main>
                </body>
                </html>
                """.formatted(title, escape(printCss), title, metadata, turns);
    }

    private String renderMetadata(ConversationPdfDocument exportDocument) {
        String providerModel = List.of(exportDocument.provider(), exportDocument.model()).stream()
                .filter(StringUtils::isNotBlank)
                .collect(joining(" · "));
        String providerLine = StringUtils.isBlank(providerModel)
                ? ""
                : "<div class=\"document-meta\">%s</div>".formatted(escape(providerModel));
        String createdLine = exportDocument.createdAt() == null
                ? ""
                : "<div class=\"document-meta\">Created %s</div>".formatted(
                        escape(DATE_FORMATTER.format(exportDocument.createdAt()))
                );
        String exported = DATE_FORMATTER.format(exportDocument.exportedAt().atZone(ZoneId.systemDefault()));
        int messageCount = exportDocument.turns().size();
        String messageLabel = messageCount == 1 ? "1 message" : "%d messages".formatted(messageCount);
        return "%s%s<div class=\"document-meta\">Exported %s · %s</div>".formatted(
                providerLine,
                createdLine,
                escape(exported),
                messageLabel
        );
    }

    private String renderTurn(
            ConversationPdfDocument.Turn turn,
            Map<String, SmilesDiagramRenderer.Result> smilesCache,
            BooleanSupplier cancelled
    ) {
        String role = turn.role() == Role.USER ? "User" : "Assistant";
        String time = turn.timestamp() == null
                ? ""
                : "<span class=\"turn-time\">%s</span>".formatted(
                        escape(DATE_FORMATTER.format(turn.timestamp().atZone(ZoneId.systemDefault())))
                );
        String text = turn.textForRendering();
        String message = renderMessage(turn.role(), text, smilesCache, cancelled);
        boolean includeRemainingContent = !cancelled.getAsBoolean();
        String status = includeRemainingContent ? renderMessageStatus(turn, text) : "";
        String media = includeRemainingContent ? renderMedia(turn.parts()) : "";
        String files = includeRemainingContent ? renderFiles(turn.parts()) : "";
        String webSearch = includeRemainingContent ? renderWebSearch(turn.assistantWebSearch(), smilesCache, cancelled) : "";
        String citations = includeRemainingContent ? renderCitations(turn.citations()) : "";
        return """
                <section class="turn %s">
                  <h2 class="turn-heading">%s%s</h2>
                  <div class="turn-content">%s%s%s%s%s%s</div>
                </section>
                """.formatted(
                turn.role() == Role.USER ? "user" : "assistant",
                role,
                time,
                message,
                status,
                media,
                files,
                webSearch,
                citations
        );
    }

    private String renderMessageStatus(ConversationPdfDocument.Turn turn, String text) {
        String notices = turn.fallbackNotices().stream()
                .filter(StringUtils::isNotBlank)
                .map(notice -> "<div class=\"message-status\">Fallback: %s</div>".formatted(escape(notice)))
                .collect(joining());
        String cancellation = turn.cancelled()
                ? "<div class=\"message-status\">Response cancelled.</div>"
                : "";
        String error = StringUtils.isNotBlank(turn.error()) && !StringUtils.defaultString(text).contains(turn.error())
                ? "<div class=\"message-status error\">%s</div>".formatted(escape(turn.error()))
                : "";
        return "%s%s%s".formatted(notices, cancellation, error);
    }

    private String renderMessage(
            Role role,
            String text,
            Map<String, SmilesDiagramRenderer.Result> smilesCache,
            BooleanSupplier cancelled
    ) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        String rendered = CodeFontResolver.withResolvedCodeFontSize(
                PRINT_CODE_FONT_SIZE,
                () -> messageHtmlRenderer.render(role, RenderMode.PREVIEW, text, false, PRINT_PALETTE)
        );
        Document document = Jsoup.parse(rendered);
        TranscriptEntryRenderer.renderCodeHighlights(document);
        prepareCodeBlocks(document);
        sanitizeLinksAndRemoteImages(document);
        renderSmilesDiagrams(document, smilesCache, cancelled);
        document.select("table.md-diagram-block:not(.md-smiles-block)").forEach(table -> table.after(
                "<div class=\"diagram-fallback-note\">Diagram source is shown because this export backend does not execute browser renderers.</div>"
        ));
        document.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        return document.body() == null ? escape(text) : document.body().html();
    }

    private void renderSmilesDiagrams(
            Document document,
            Map<String, SmilesDiagramRenderer.Result> smilesCache,
            BooleanSupplier cancelled
    ) {
        document.select("table.md-smiles-block").stream()
                .takeWhile(table -> !cancelled.getAsBoolean())
                .forEach(table -> {
            Element pre = table.selectFirst("pre");
            String source = pre == null ? "" : pre.text().trim();
            SmilesDiagramRenderer.Result result = smilesCache.computeIfAbsent(
                    source,
                    SmilesDiagramRenderer.instance()::render
            );
            Element sourceNode = table.parent() != null && table.parent().hasClass("code-block-shell")
                    ? table.parent()
                    : table;
            if (!result.successful()) {
                sourceNode.after(new Element("div")
                        .addClass("diagram-fallback-note")
                        .text(smilesFailureMessage(result.failure())));
                return;
            }

            Element figure = new Element("figure")
                    .addClass("smiles-diagram")
                    .addClass("smiles-diagram-%s".formatted(
                            result.displaySize().name().toLowerCase(Locale.ROOT)
                    ));
            figure.appendElement("img")
                    .attr("src", "data:image/png;base64,%s".formatted(
                            Base64.getEncoder().encodeToString(result.png())
                    ))
                    .attr("alt", "SMILES chemical structure");
            Element caption = figure.appendElement("figcaption").appendText("SMILES: ");
            caption.appendElement("code").text(source);
            sourceNode.replaceWith(figure);
        });
    }

    private String smilesFailureMessage(SmilesDiagramRenderer.Failure failure) {
        return switch (failure) {
            case BLANK -> "SMILES source is blank and could not be rendered.";
            case TOO_LARGE -> "SMILES source is too large to render.";
            case INVALID -> "SMILES diagram could not be rendered.";
            case UNAVAILABLE -> "SMILES renderer is unavailable; source is shown instead.";
        };
    }

    private void prepareCodeBlocks(Document document) {
        document.select("table.md-code-block").forEach(table -> {
            Elements rows = table.select("tr");
            if (rows.size() > 1) {
                rows.first().addClass("code-header");
                rows.last().addClass("code-body");
                return;
            }
            rows.addClass("code-body");
        });
    }

    private void sanitizeLinksAndRemoteImages(Document document) {
        document.select("a[href]").forEach(anchor -> {
            if (!(anchor.previousSibling() instanceof TextNode textNode)
                    || !textNode.getWholeText().endsWith("!")
                    || !isSafeHttpUri(anchor.attr("href"))
            ) {
                return;
            }
            String text = textNode.getWholeText();
            textNode.text(text.substring(0, text.length() - 1));
            anchor.text("%s — remote image".formatted(anchor.text()));
        });
        document.select("img").forEach(image -> {
            String source = image.attr("src");
            Element replacement = new Element("div").addClass("remote-image");
            if (isSafeHttpUri(source)) {
                replacement.appendText("Remote image: ");
                replacement.appendElement("a").attr("href", source).text(source);
            } else {
                replacement.text("Image omitted because it is not a persisted local attachment.");
            }
            image.replaceWith(replacement);
        });
        document.select("a[href]").forEach(anchor -> {
            String href = anchor.attr("href");
            if (isSafeLinkUri(href)) {
                anchor.attr("href", href.trim());
            } else {
                anchor.removeAttr("href");
            }
        });
        styleNumericCitations(document);
    }

    private void styleNumericCitations(Document document) {
        document.select("a[href]").forEach(anchor -> {
            Matcher label = NUMERIC_CITATION_LABEL.matcher(anchor.text().trim());
            if (!label.matches() || !isSafeHttpUri(anchor.attr("href"))) {
                return;
            }
            var superscript = new Element("sup").addClass("source-citation-print");
            anchor.replaceWith(superscript);
            anchor.addClass("source-citation-link").text("[%s]".formatted(label.group(1)));
            superscript.appendChild(anchor);
        });
    }

    private String renderMedia(List<ContentPart> parts) {
        return parts.stream()
                .filter(part -> part instanceof ImagePart || part instanceof GeneratedImagePart)
                .map(this::renderImage)
                .collect(joining("\n"));
    }

    private String renderImage(ContentPart part) {
        AttachmentRef reference;
        String altText;
        if (part instanceof GeneratedImagePart generatedImage) {
            reference = generatedImage.attachmentRef();
            altText = generatedImage.altText();
        } else if (part instanceof ImagePart image) {
            reference = image.attachmentRef();
            altText = StringUtils.defaultIfBlank(reference.originalName(), "Attached image");
        } else {
            return "";
        }
        return imageDataUri(reference)
                .map(dataUri -> """
                        <figure class="local-image">
                          <img src="%s" alt="%s" />
                          <figcaption>%s</figcaption>
                        </figure>
                        """.formatted(dataUri, escapeAttribute(altText), escape(altText)))
                .orElseGet(() -> "<div class=\"missing-asset\">Image unavailable: %s</div>".formatted(
                        escape(StringUtils.defaultIfBlank(reference.originalName(), altText))
                ));
    }

    private Optional<String> imageDataUri(AttachmentRef reference) {
        if (reference == null || StringUtils.isBlank(reference.storagePath())) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(reference.storagePath());
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) {
                return Optional.empty();
            }
            PdfExportImageFormat imageFormat = PdfExportImageFormat.detect(path).orElse(null);
            String mimeType;
            byte[] bytes;
            if (imageFormat != null) {
                bytes = Files.readAllBytes(path);
                mimeType = imageFormat.getMimeType();
            } else {
                try (var output = new ByteArrayOutputStream()) {
                    if (!ImageIO.write(image, "png", output)) {
                        return Optional.empty();
                    }
                    bytes = output.toByteArray();
                    mimeType = "image/png";
                }
            }
            return Optional.of("data:%s;base64,%s".formatted(
                    mimeType,
                    Base64.getEncoder().encodeToString(bytes)
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String renderFiles(List<ContentPart> parts) {
        List<FilePart> files = parts.stream().filter(FilePart.class::isInstance).map(FilePart.class::cast).toList();
        if (files.isEmpty()) {
            return "";
        }
        String items = files.stream()
                .map(FilePart::attachmentRef)
                .map(reference -> "<li>%s%s</li>".formatted(
                        escape(StringUtils.defaultIfBlank(reference.originalName(), "Attachment")),
                        reference.sizeBytes() > 0 ? " (%d bytes)".formatted(reference.sizeBytes()) : ""
                ))
                .collect(joining());
        return "<div class=\"attachment-heading\">Attachments</div><ul class=\"attachment-list\">%s</ul>".formatted(items);
    }

    private String renderWebSearch(
            String activity,
            Map<String, SmilesDiagramRenderer.Result> smilesCache,
            BooleanSupplier cancelled
    ) {
        if (StringUtils.isBlank(activity)) {
            return "";
        }
        return "<div class=\"attachment-heading\">Web Search</div><div class=\"web-search-activity\">%s</div>".formatted(
                renderMessage(Role.ASSISTANT, activity, smilesCache, cancelled)
        );
    }

    private String renderCitations(List<CitationRef> citations) {
        if (citations == null || citations.isEmpty()) {
            return "";
        }
        String items = citations.stream().map(citation -> {
            String label = "%d. %s".formatted(citation.number(), citation.displayTitle());
            String location = citation.locationLabel();
            String linkedLabel = isSafeHttpUri(citation.url())
                    ? "<a href=\"%s\">%s</a>".formatted(escapeAttribute(citation.url()), escape(label))
                    : escape(label);
            String locationHtml = StringUtils.isBlank(location) ? "" : " — %s".formatted(escape(location));
            String quoted = StringUtils.isBlank(citation.citedText())
                    ? ""
                    : "<div>%s</div>".formatted(escape(citation.citedText()));
            return "<li>%s%s%s</li>".formatted(linkedLabel, locationHtml, quoted);
        }).collect(joining());
        return "<div class=\"citation-heading\">Sources</div><ul class=\"citation-list\">%s</ul>".formatted(items);
    }

    private boolean isSafeLinkUri(String value) {
        if (StringUtils.isBlank(value) || value.startsWith("#")) {
            return true;
        }
        return ExternalLinkSupport.isAllowedExternalLink(value);
    }

    private boolean isSafeHttpUri(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        if (!ExternalLinkSupport.isAllowedExternalLink(value)) {
            return false;
        }
        String scheme = URI.create(value.trim()).getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private static String requiredResourceText(String path) {
        try (InputStream input = ConversationPrintHtmlRenderer.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing PDF export resource: %s".formatted(path));
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read PDF export resource: %s".formatted(path), e);
        }
    }

    private static String cssString(String value) {
        return StringUtils.defaultIfBlank(value, "Conversation")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("&", "\\26 ")
                .replace("<", "\\3c ")
                .replace(">", "\\3e ")
                .replace("\r", " ")
                .replace("\n", " ")
                .replace("\f", " ");
    }

    private static String escape(String value) {
        return Entities.escape(StringUtils.defaultString(value));
    }

    private static String escapeAttribute(String value) {
        return Entities.escape(StringUtils.defaultString(value), new Document.OutputSettings().syntax(Document.OutputSettings.Syntax.xml));
    }
}
