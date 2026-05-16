package com.github.drafael.chat4j.chat.message;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.chat.CodeFontResolver;
import com.github.drafael.chat4j.chat.MarkdownPaletteResolver;
import com.github.drafael.chat4j.chat.MarkdownRenderer;
import com.github.drafael.chat4j.chat.Palette;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.util.Fonts;
import org.apache.commons.lang3.StringUtils;

import static java.util.stream.Collectors.joining;

final class MessageHtmlRenderer {

    String render(Role role, AssistantRenderMode assistantRenderMode, String text, boolean isDark) {
        Palette palette = MarkdownPaletteResolver.resolve(isDark);
        String safeText = StringUtils.defaultString(text);

        if (role == Role.USER) {
            return toUserHtml(safeText, palette, isDark);
        }

        if (assistantRenderMode == AssistantRenderMode.MARKDOWN) {
            return toEscapedHtml(safeText, palette, true);
        }

        return MarkdownRenderer.toHtml(safeText, isDark);
    }

    String toEscapedHtml(String text, Palette palette, boolean monospaced) {
        String escaped = escapeHtml(text).replace("\n", "<br>");

        String fontFamily = monospaced ? palette.monoFontFamily() : palette.baseFontFamily();
        int fontSize = monospaced ? CodeFontResolver.resolveCodeFontSize() : Fonts.scale(Fonts.SIZE_SMALL);
        return "<html><head><style>body { font-family: %s; font-size: %dpx; line-height: 1.4; color: %s; margin: 0; padding: 0; }</style></head><body>%s</body></html>"
                .formatted(fontFamily, fontSize, palette.textColor(), escaped);
    }

    String toUserHtml(String text, Palette palette, boolean isDark) {
        String badgeBackground = isDark ? "#2d4f8f" : "#dbeafe";
        String badgeText = isDark ? "#dbeafe" : "#1e3a8a";
        String fallbackBackground = isDark ? "#5c3a16" : "#ffedd5";
        String fallbackText = isDark ? "#fed7aa" : "#9a3412";

        String body = text.lines()
                .map(line -> toUserLineHtml(line, badgeBackground, badgeText, fallbackBackground, fallbackText))
                .collect(joining());

        int bodyFontSize = Fonts.scale(Fonts.SIZE_SMALL);
        int badgeFontSize = Fonts.scale(Fonts.SIZE_BADGE);
        return "<html><head><style>body { font-family: %s; font-size: %dpx; line-height: 1.45; color: %s; margin: 0; padding: 0; }.line { margin: 0 0 3px 0; }.badge { display: inline-block; border-radius: 999px; padding: 1px 6px; font-size: %dpx; font-weight: 700; letter-spacing: 0.04em; margin-right: 6px; }.skill { background: %s; color: %s; }.fallback { background: %s; color: %s; }</style></head><body>%s</body></html>"
                .formatted(
                        palette.baseFontFamily(),
                        bodyFontSize,
                        palette.textColor(),
                        badgeFontSize,
                        badgeBackground,
                        badgeText,
                        fallbackBackground,
                        fallbackText,
                        body
                );
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
            return "<div class='line'><span class='badge fallback' style='background: %s; color: %s;'>FALLBACK</span>%s</div>"
                    .formatted(fallbackBadgeBackground, fallbackBadgeText, escapeHtml(line.substring(11).trim()));
        }

        return "<div class='line'>%s</div>".formatted(escapeHtml(line));
    }

    private String escapeHtml(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
