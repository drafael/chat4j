package com.github.drafael.chat4j.chat.conversation.webview.shared;

import com.github.drafael.chat4j.chat.content.HighlightJsCodeRenderer;
import com.github.drafael.chat4j.chat.content.KatexMathRenderer;
import com.github.drafael.chat4j.chat.content.MathFallbackTextRenderer;
import com.github.drafael.chat4j.chat.content.MessageHtmlRenderer;
import com.github.drafael.chat4j.chat.conversation.ConversationAttachment;
import com.github.drafael.chat4j.chat.conversation.ConversationEntry;
import com.github.drafael.chat4j.chat.conversation.ConversationEntryKind;
import com.github.drafael.chat4j.chat.render.Palette;
import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.BooleanSupplier;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.joining;

public final class TranscriptEntryRenderer {

    private static final KatexMathRenderer KATEX_RENDERER = KatexMathRenderer.instance();
    private static final HighlightJsCodeRenderer HIGHLIGHT_RENDERER = HighlightJsCodeRenderer.instance();
    private static final Pattern PLAIN_CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");

    private final MessageHtmlRenderer messageHtmlRenderer = new MessageHtmlRenderer();

    public String renderEntriesHtml(TranscriptRenderSnapshot snapshot) {
        return snapshot.entries().stream()
                .map(entry -> renderEntrySafely(entry, snapshot))
                .collect(joining("\n"));
    }

    public String renderEntriesHtml(TranscriptRenderSnapshot snapshot, BooleanSupplier shouldContinue) {
        StringBuilder html = new StringBuilder();
        for (ConversationEntry entry : snapshot.entries()) {
            if (!shouldContinue.getAsBoolean()) {
                return null;
            }
            if (!html.isEmpty()) {
                html.append("\n");
            }
            html.append(renderEntrySafely(entry, snapshot));
        }
        return html.toString();
    }

    private String renderEntrySafely(ConversationEntry entry, TranscriptRenderSnapshot snapshot) {
        try {
            return renderEntry(entry, snapshot);
        } catch (Exception e) {
            return renderFallbackEntry(entry);
        }
    }

    private String renderFallbackEntry(ConversationEntry entry) {
        String roleClass = entry.role() == Role.USER ? "user" : "assistant";
        String text = escapeHtml(entry.text()).replace("\n", "<br>");
        String attachments = renderAttachmentStripHtml(entry.attachments());
        return """
                <section class="row %s" data-message-index="%d">
                  <div class="message-shell">
                    %s
                    <div class="message %s">%s</div>
                  </div>
                </section>
                """.formatted(roleClass, entry.messageIndex(), attachments, roleClass, text);
    }

    private String renderEntry(ConversationEntry entry, TranscriptRenderSnapshot snapshot) {
        if (entry.kind() == ConversationEntryKind.ACTIVITY) {
            String renderedActivity = renderEntryContentHtml(Role.ASSISTANT, entry.text(), snapshot);
            Document activityDocument = Jsoup.parse(renderedActivity);
            prepareRenderedDocument(activityDocument, emptyList());
            String activityBody = activityDocument.body() == null ? escapeHtml(entry.text()) : activityDocument.body().html();
            String content = StringUtils.isBlank(entry.text())
                    ? ""
                    : "<div class=\"activity-content\"><div class=\"message assistant\">%s</div></div>".formatted(activityBody);
            String openAttribute = entry.collapsed() ? "" : " open";
            return """
                    <section class="row activity">
                      <details class="activity-box"%s>
                        <summary>%s<button class="activity-copy-button" title="Copy activity" data-action="copy-activity"><span class="icon copy" aria-hidden="true"></span></button></summary>
                        %s
                      </details>
                    </section>
                    """.formatted(openAttribute, escapeHtml(entry.title()), content);
        }

        String rendered = entry.parts().isEmpty()
                ? renderEntryContentHtml(entry.role(), entry.text(), snapshot)
                : messageHtmlRenderer.render(entry.role(), snapshot.renderMode(), entry.parts(), snapshot.dark(), snapshot.palette());
        Document document = Jsoup.parse(rendered);
        prepareRenderedDocument(document, entry.meta().citations());
        String body = document.body() == null ? escapeHtml(entry.text()) : document.body().html();
        String roleClass = entry.role() == Role.USER ? "user" : "assistant";
        String attachments = renderAttachmentStripHtml(entry.attachments());
        String actions = entry.messageIndex() < 0
                ? ""
                : """
                  <div class="message-actions" data-message-index="%d">
                    <button class="message-action-button" title="Copy message" data-action="copy" data-message-index="%d"><span class="icon copy" aria-hidden="true"></span></button>
                    <button class="message-action-button" title="%s" data-action="regenerate" data-message-index="%d"><span class="icon regenerate" aria-hidden="true"></span></button>
                  </div>
                """.formatted(
                        entry.messageIndex(),
                        entry.messageIndex(),
                        entry.role() == Role.USER ? "Regenerate response" : "Regenerate this response",
                        entry.messageIndex()
                );
        return """
                <section class="row %s" data-message-index="%d">
                  <div class="message-shell">
                    %s
                    %s
                    <div class="message %s">%s</div>
                  </div>
                </section>
                """.formatted(roleClass, entry.messageIndex(), attachments, actions, roleClass, body);
    }

    public static String renderAttachmentStripHtml(List<ConversationAttachment> attachments) {
        return TranscriptAttachmentRenderer.renderAttachmentStripHtml(attachments);
    }

    private String renderEntryContentHtml(Role role, String text, TranscriptRenderSnapshot snapshot) {
        return snapshot.renderMode() == RenderMode.MARKDOWN
                ? renderRawMarkdownSourceHtml(text, snapshot)
                : messageHtmlRenderer.render(role, snapshot.renderMode(), text, snapshot.dark(), snapshot.palette());
    }

    private String renderRawMarkdownSourceHtml(String text, TranscriptRenderSnapshot snapshot) {
        Palette palette = snapshot.palette();
        int languageFontSize = snapshot.chrome().languageFontSize();
        int codeFontSize = snapshot.codeFontSize();
        String source = escapeHtml(StringUtils.defaultString(text));
        return """
                <html><body><table class="md-code-block" data-code-language="markdown" width="100%%" cellpadding="0" cellspacing="0" border="0" style="margin: 6px 0;">
                  <tr><td bgcolor="%s" style="border: 1px solid %s; border-bottom: none; padding: 2px 8px; font-size: %dpx;"><font face="%s" color="%s">markdown</font></td></tr>
                  <tr><td bgcolor="%s" style="border: 1px solid %s; padding: 8px 12px;"><pre style="margin: 0;"><font face="%s" color="%s" style="font-size: %dpx;">%s</font></pre></td></tr>
                </table></body></html>
                """.formatted(
                palette.codeHeaderBg(),
                palette.hrColor(),
                languageFontSize,
                palette.baseFontFamilyAttr(),
                palette.mutedTextColor(),
                palette.codeBg(),
                palette.hrColor(),
                palette.monoFontFamilyAttr(),
                palette.codeText(),
                codeFontSize,
                source
        );
    }

    private void prepareRenderedDocument(Document document, List<CitationRef> citations) {
        renderCodeHighlights(document);
        renderMathFallbacks(document);
        replaceGeneratedImageSources(document);
        document.select("table.md-table").wrap("<div class=\"table-wrap\"></div>");
        annotateSourceLinks(document);
        annotateCitationMetadata(document, citations);
        removeAdjacentDuplicateSourceCitations(document);
        document.select("table.md-code-block").forEach(table -> {
            var rows = table.select("tr");
            if (rows.size() > 1) {
                table.addClass("with-header");
                rows.first().addClass("code-header");
                rows.last().addClass("code-body");
                return;
            }
            table.addClass("without-header");
            rows.addClass("code-body");
        });
    }

    static void replaceGeneratedImageSources(Document document) {
        document.select("img.generated-image").forEach(image -> {
            Element attachmentElement = image.closest("[data-attachment-path]");
            if (attachmentElement == null) {
                return;
            }
            String dataUri = TranscriptAttachmentRenderer.generatedImageDataUri(attachmentElement.attr("data-attachment-path"));
            if (StringUtils.isBlank(dataUri)) {
                return;
            }
            image.attr("src", dataUri);
        });
    }

    public static void renderCodeHighlights(Document document) {
        document.select("table.md-code-block:not(.md-latex-block):not(.md-diagram-block)").forEach(table -> {
            String language = StringUtils.defaultIfBlank(table.attr("data-code-language"), codeBlockHeaderText(table));
            Element pre = table.selectFirst("pre");
            if (pre == null) {
                return;
            }

            HIGHLIGHT_RENDERER.render(pre.wholeText(), language).ifPresent(html -> {
                String normalizedLanguage = HIGHLIGHT_RENDERER.normalizeLanguage(language);
                pre.html(html);
                pre.addClass("hljs");
                pre.addClass("language-%s".formatted(normalizedLanguage));
                pre.attr("data-chat4j-highlighted", "server");
            });
        });
    }

    private static String codeBlockHeaderText(Element table) {
        var rows = table.select("tr");
        if (rows.size() <= 1) {
            return "";
        }
        Element header = rows.first();
        return header == null ? "" : header.text();
    }

    public static void renderMathFallbacks(Document document) {
        document.select("code.md-latex-inline").forEach(code ->
                KATEX_RENDERER.render(code.text(), false).ifPresentOrElse(html -> {
                    Element replacement = new Element("span")
                            .addClass("chat4j-math-inline")
                            .attr("data-chat4j-math-rendered", "server");
                    replacement.html(html);
                    code.replaceWith(replacement);
                }, () -> {
                    Element replacement = new Element("span")
                            .addClass("chat4j-math-text-fallback")
                            .attr("data-chat4j-math-rendered", "text-fallback");
                    replacement.text(MathFallbackTextRenderer.readableText(code.text(), false));
                    code.replaceWith(replacement);
                })
        );

        document.select("table.md-latex-block").forEach(table -> {
            Element pre = table.selectFirst("pre");
            if (pre == null) {
                return;
            }
            KATEX_RENDERER.render(pre.text(), true).ifPresentOrElse(html -> {
                Element replacement = new Element("div")
                        .addClass("chat4j-math-display")
                        .attr("data-chat4j-math-rendered", "server");
                replacement.html(html);
                table.replaceWith(replacement);
            }, () -> {
                Element replacement = new Element("div")
                        .addClass("chat4j-math-display chat4j-math-text-fallback")
                        .attr("data-chat4j-math-rendered", "text-fallback");
                replacement.text(MathFallbackTextRenderer.readableText(pre.text(), true));
                table.replaceWith(replacement);
            });
        });
    }

    private void annotateCitationMetadata(Document document, List<CitationRef> citations) {
        if (citations == null || citations.isEmpty()) {
            return;
        }

        Map<Integer, CitationRef> citationsByNumber = new LinkedHashMap<>();
        citations.stream()
                .filter(citation -> citation != null && citation.number() > 0)
                .forEach(citation -> citationsByNumber.putIfAbsent(citation.number(), citation));
        if (citationsByNumber.isEmpty()) {
            return;
        }

        document.select("a").forEach(anchor -> citationNumber(anchor.text())
                .map(citationsByNumber::get)
                .ifPresent(citation -> applyCitationMetadata(anchor, citation)));

        if (document.body() != null) {
            annotatePlainCitationMarkers(document.body(), citationsByNumber);
            document.select("a.citation-ref").forEach(anchor -> citationNumber(anchor.text())
                    .map(citationsByNumber::get)
                    .ifPresent(citation -> applyCitationMetadata(anchor, citation)));
        }
    }

    private void annotatePlainCitationMarkers(Element root, Map<Integer, CitationRef> citationsByNumber) {
        root.textNodes().forEach(textNode -> {
            if (hasCitationIgnoredAncestor(textNode)) {
                return;
            }

            String text = textNode.getWholeText();
            Matcher matcher = PLAIN_CITATION_PATTERN.matcher(text);
            if (!matcher.find()) {
                return;
            }

            StringBuilder replacement = new StringBuilder();
            int cursor = 0;
            matcher.reset();
            while (matcher.find()) {
                int number = Integer.parseInt(matcher.group(1));
                CitationRef citation = citationsByNumber.get(number);
                if (citation == null) {
                    continue;
                }

                replacement.append(escapeHtml(text.substring(cursor, matcher.start())));
                replacement.append(citationAnchorHtml(citation));
                cursor = matcher.end();
            }
            replacement.append(escapeHtml(text.substring(cursor)));
            if (cursor == 0) {
                return;
            }

            List<Node> nodes = Jsoup.parseBodyFragment(replacement.toString()).body().childNodes();
            nodes.forEach(node -> textNode.before(node.clone()));
            textNode.remove();
        });

        root.children().forEach(child -> annotatePlainCitationMarkers(child, citationsByNumber));
    }

    private boolean hasCitationIgnoredAncestor(TextNode textNode) {
        Element parent = textNode.parent();
        while (parent != null) {
            String tagName = parent.tagName();
            if (Strings.CI.equalsAny(tagName, "a", "code", "pre", "table")) {
                return true;
            }
            parent = parent.parent();
        }
        return false;
    }

    private String citationAnchorHtml(CitationRef citation) {
        String href = citation.kind() == CitationKind.WEB && isHttpUrl(citation.url())
                ? " href=\"%s\"".formatted(escapeHtmlAttribute(citation.url()))
                : "";
        return "<a%s class=\"source-citation citation-ref\">%d</a>".formatted(href, citation.number());
    }

    private Optional<Integer> citationNumber(String text) {
        String stripped = stripCitationBrackets(text);
        if (!stripped.matches("\\d+")) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(stripped));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private void applyCitationMetadata(Element anchor, CitationRef citation) {
        anchor.addClass("source-citation");
        anchor.addClass("citation-ref");
        anchor.text(String.valueOf(citation.number()));
        if (citation.kind() == CitationKind.WEB && isHttpUrl(citation.url())) {
            anchor.attr("href", citation.url());
            anchor.attr("data-source-url", citation.url());
            anchor.attr("data-source-domain", sourceDomain(citation.url()));
        } else {
            anchor.removeAttr("href");
            anchor.attr("role", "button");
            anchor.attr("tabindex", "0");
            anchor.attr("data-source-url", "citation:%d".formatted(citation.number()));
            anchor.attr("data-source-domain", citation.locationLabel());
        }
        anchor.attr("data-source-title", StringUtils.defaultIfBlank(citation.displayTitle(), "Citation %d".formatted(citation.number())));
        anchor.attr("data-source-snippet", citationSnippet(citation));
        anchor.attr("title", citationTitle(citation));
    }

    private String citationSnippet(CitationRef citation) {
        String citedText = StringUtils.abbreviate(StringUtils.trimToEmpty(citation.citedText()), 360);
        if (StringUtils.isNotBlank(citedText)) {
            return citedText;
        }
        return StringUtils.defaultIfBlank(citation.locationLabel(), citation.displayTitle());
    }

    private String citationTitle(CitationRef citation) {
        String location = citation.locationLabel();
        return StringUtils.isBlank(location)
                ? citation.displayTitle()
                : "%s — %s".formatted(citation.displayTitle(), location);
    }

    private void annotateSourceLinks(Document document) {
        Map<String, SourcePreview> previewsByUrl = new LinkedHashMap<>();
        document.select("a[href]").stream()
                .filter(anchor -> isHttpUrl(anchor.attr("href")))
                .forEach(anchor -> previewsByUrl.merge(anchor.attr("href"), sourcePreview(anchor), this::preferSourcePreview));

        document.select("a[href]").forEach(anchor -> {
            SourcePreview preview = previewsByUrl.get(anchor.attr("href"));
            if (preview == null) {
                return;
            }

            anchor.addClass("source-link");
            if (isCitationAnchor(anchor)) {
                anchor.addClass("source-citation");
                anchor.text(stripCitationBrackets(anchor.text()));
            }
            anchor.attr("data-source-title", preview.title());
            anchor.attr("data-source-domain", preview.domain());
            anchor.attr("data-source-url", preview.url());
            anchor.attr("data-source-snippet", preview.snippet());
        });
    }

    public static void removeAdjacentDuplicateSourceCitations(Document document) {
        document.select("a.source-citation").forEach(anchor -> {
            Element previous = previousSourceCitation(anchor);
            if (previous == null || !sameCitation(previous, anchor)) {
                return;
            }

            removeBlankPreviousSibling(anchor);
            anchor.remove();
        });
    }

    private static Element previousSourceCitation(Element anchor) {
        Node previous = anchor.previousSibling();
        while (previous instanceof TextNode textNode && StringUtils.isBlank(textNode.text())) {
            previous = previous.previousSibling();
        }
        return previous instanceof Element element && element.hasClass("source-citation") ? element : null;
    }

    private static boolean sameCitation(Element first, Element second) {
        String firstUrl = StringUtils.defaultIfBlank(first.attr("data-source-url"), first.attr("href"));
        String secondUrl = StringUtils.defaultIfBlank(second.attr("data-source-url"), second.attr("href"));
        return Strings.CS.equals(firstUrl, secondUrl) && Strings.CS.equals(first.text(), second.text());
    }

    private static void removeBlankPreviousSibling(Element anchor) {
        Node previous = anchor.previousSibling();
        if (previous instanceof TextNode textNode && StringUtils.isBlank(textNode.text())) {
            textNode.remove();
        }
    }

    private SourcePreview preferSourcePreview(SourcePreview existing, SourcePreview candidate) {
        boolean existingNumeric = isCitationText(existing.title());
        boolean candidateNumeric = isCitationText(candidate.title());
        if (existingNumeric && !candidateNumeric) {
            return candidate;
        }
        if (!candidateNumeric && candidate.snippet().length() > existing.snippet().length()) {
            return candidate;
        }
        return existing;
    }

    private boolean isCitationAnchor(Element anchor) {
        return isCitationText(anchor.text());
    }

    private boolean isCitationText(String text) {
        return stripCitationBrackets(text).matches("\\d+");
    }

    private String stripCitationBrackets(String text) {
        return Strings.CS.removeEnd(Strings.CS.removeStart(StringUtils.trimToEmpty(text), "["), "]");
    }

    private SourcePreview sourcePreview(Element anchor) {
        String href = anchor.attr("href");
        String title = StringUtils.defaultIfBlank(anchor.text(), sourceDomain(href));
        String domain = sourceDomain(href);
        String context = sourceContext(anchor);
        String snippet = sourceSnippet(context, title, href);
        return new SourcePreview(title, domain, href, snippet);
    }

    private String sourceContext(Element anchor) {
        Element listItem = anchor.closest("li");
        if (listItem != null) {
            return listItem.text();
        }
        Element paragraph = anchor.closest("p");
        return paragraph == null ? anchor.parent() == null ? anchor.text() : anchor.parent().text() : paragraph.text();
    }

    private String sourceSnippet(String context, String title, String url) {
        String snippet = StringUtils.defaultString(context)
                .replaceFirst("^\\s*\\d+\\.\\s*", "")
                .trim();
        if (Strings.CI.startsWith(snippet, title)) {
            snippet = snippet.substring(title.length()).trim();
        }
        snippet = snippet.replaceFirst("^[—–-]\\s*", "").trim();
        if (StringUtils.isBlank(snippet) || Strings.CS.equals(snippet, title)) {
            snippet = url;
        }
        return StringUtils.abbreviate(snippet, 260);
    }

    private boolean isHttpUrl(String href) {
        return Strings.CI.startsWith(href, "http://") || Strings.CI.startsWith(href, "https://");
    }

    private String sourceDomain(String url) {
        try {
            String host = URI.create(url).getHost();
            return StringUtils.defaultIfBlank(Strings.CS.removeStart(host, "www."), url);
        } catch (Exception e) {
            return url;
        }
    }

    private record SourcePreview(String title, String domain, String url, String snippet) {
        @Override
        public String toString() {
            return "SourcePreview[title=%s, domain=%s, url=<masked>, snippet=<masked>]".formatted(title, domain);
        }
    }

    private static String escapeHtml(String text) {
        return StringUtils.defaultString(text)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String escapeHtmlAttribute(String text) {
        return escapeHtml(text)
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
