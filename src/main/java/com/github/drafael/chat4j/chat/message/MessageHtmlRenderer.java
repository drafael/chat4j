package com.github.drafael.chat4j.chat.message;

import com.github.drafael.chat4j.chat.CodeFontResolver;
import com.github.drafael.chat4j.chat.MarkdownPaletteResolver;
import com.github.drafael.chat4j.chat.MarkdownRenderer;
import com.github.drafael.chat4j.chat.Palette;
import com.github.drafael.chat4j.chat.RenderMode;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.util.Fonts;
import org.apache.commons.lang3.StringUtils;

import static java.util.stream.Collectors.joining;

/**
 * Internal Chat4J HTML renderer for chat message content.
 * <p>
 * This class is public so development tools and future content-view engines can reuse the same rendering path.
 * It is not a stable external extension API.
 */
public final class MessageHtmlRenderer {

    public MessageHtmlRenderer() {
    }

    public String render(Role role, RenderMode renderMode, String text, boolean isDark) {
        return render(role, renderMode, text, isDark, MarkdownPaletteResolver.resolve(isDark));
    }

    String render(Role role, RenderMode renderMode, String text, boolean isDark, Palette palette) {
        String safeText = StringUtils.defaultString(text);

        return switch (role) {
            case USER -> renderMode == RenderMode.MARKDOWN
                    ? toEscapedHtml(safeText, palette, true)
                    : toUserMarkdownHtml(safeText, palette, isDark);
            case null, default -> renderMode == RenderMode.MARKDOWN
                    ? toEscapedHtml(safeText, palette, true)
                    : MarkdownRenderer.toHtml(safeText, palette);
        };
    }

    String toEscapedHtml(String text, Palette palette, boolean monospaced) {
        String escaped = escapeHtml(text);

        String fontFamily = monospaced ? palette.monoFontFamily() : palette.baseFontFamily();
        int fontSize = monospaced ? CodeFontResolver.resolveCodeFontSize() : Fonts.scale(Fonts.SIZE_SMALL);
        return "<html><head><style>body { color: %s; margin: 0; padding: 0; } pre { font-family: %s; font-size: %dpx; line-height: 1.7; margin: 0; padding: 0; white-space: pre-wrap; word-wrap: break-word; }</style></head><body><pre>%s</pre></body></html>"
                .formatted(palette.textColor(), fontFamily, fontSize, escaped);
    }

    String toUserMarkdownHtml(String text, Palette palette, boolean isDark) {
        String badgeBackground = isDark ? "#2d4f8f" : "#dbeafe";
        String badgeText = isDark ? "#dbeafe" : "#1e3a8a";
        String fallbackBackground = isDark ? "#5c3a16" : "#ffedd5";
        String fallbackText = isDark ? "#fed7aa" : "#9a3412";
        StringBuilder badgeHtml = new StringBuilder();
        String markdown = text.lines()
                .filter(line -> appendBadgeLine(line, badgeHtml, badgeBackground, badgeText, fallbackBackground, fallbackText))
                .collect(joining("\n"));

        String html = MarkdownRenderer.toHtml(markdown, palette);
        if (badgeHtml.isEmpty()) {
            return html;
        }

        return html
                .replace("</style>", "%s</style>".formatted(userBadgeCss(badgeFontSize(), badgeBackground, badgeText, fallbackBackground, fallbackText)))
                .replace("<body>", "<body>%s".formatted(badgeHtml));
    }

    private boolean appendBadgeLine(String line,
                                    StringBuilder badgeHtml,
                                    String skillBadgeBackground,
                                    String skillBadgeText,
                                    String fallbackBadgeBackground,
                                    String fallbackBadgeText
    ) {
        if (line.startsWith("[SKILL] ") || line.startsWith("[FALLBACK] ")) {
            badgeHtml.append(toUserLineHtml(line, skillBadgeBackground, skillBadgeText, fallbackBadgeBackground, fallbackBadgeText));
            return false;
        }
        return true;
    }

    private String userBadgeCss(int badgeFontSize,
                                String badgeBackground,
                                String badgeText,
                                String fallbackBackground,
                                String fallbackText
    ) {
        return """
                .line { margin: 0 0 3px 0; }
                .notice { margin: 0 0 6px 0; }
                .badge { display: inline-block; border-radius: 999px; padding: 1px 6px; font-size: %dpx; font-weight: 700; letter-spacing: 0.04em; margin-right: 6px; }
                .skill { background: %s; color: %s; }
                .fallback { background: %s; color: %s; }
                """.formatted(badgeFontSize, badgeBackground, badgeText, fallbackBackground, fallbackText);
    }

    private int badgeFontSize() {
        return Math.max(9, CodeFontResolver.resolveCodeFontSize() - 1);
    }

    private String toUserLineHtml(String line,
                                  String skillBadgeBackground,
                                  String skillBadgeText,
                                  String fallbackBadgeBackground,
                                  String fallbackBadgeText
    ) {
        if (StringUtils.isBlank(line)) {
            return "<div class='line'>&nbsp;</div>";
        }

        if (line.startsWith("[SKILL] ")) {
            return "<div class='line'><span class='badge skill' style='background: %s; color: %s;'>SKILL</span>%s</div>"
                    .formatted(skillBadgeBackground, skillBadgeText, escapeHtml(line.substring(8).trim()));
        }

        if (line.startsWith("[FALLBACK] ")) {
            String notice = line.substring(11).trim();
            return "<div class='line notice fallback-notice' style='margin: 0 0 6px 0; font-size: 0.92em; line-height: 1.35;'><span class='badge fallback' style='background: %s; color: %s; border-radius: 999px; padding: 1px 6px; font-weight: 700; letter-spacing: 0.04em; margin-right: 6px;'>ATTACHMENT</span><span style='opacity: 0.82;' title='%s'>%s</span></div>"
                    .formatted(
                            fallbackBadgeBackground,
                            fallbackBadgeText,
                            escapeHtmlAttribute(notice),
                            escapeHtml(compactFallbackNotice(notice))
                    );
        }

        return "<div class='line'>%s</div>".formatted(escapeHtml(line));
    }

    private String compactFallbackNotice(String notice) {
        String normalized = StringUtils.defaultString(notice).toLowerCase();
        if ((normalized.contains("supports rich input") && normalized.contains("file upload mapping"))
                || normalized.contains("native file upload is not mapped")) {
            return "Extracted text sent · native upload not mapped";
        }
        if (normalized.contains("uses text-only fallback for file attachments")
                || normalized.contains("native file upload is unavailable")) {
            return "Extracted text sent · native upload unavailable";
        }
        if (normalized.contains("text-only image references")) {
            return "Image sent as a text reference";
        }
        return notice;
    }

    private String escapeHtml(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String escapeHtmlAttribute(String text) {
        return escapeHtml(text)
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
