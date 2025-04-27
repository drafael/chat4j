package com.github.drafael.chat4j.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MarkdownInlineRenderer {

    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`]+?)`");
    private static final Pattern INLINE_CODE_TOKEN_PATTERN = Pattern.compile("@@INLINE_CODE_(\\d+)@@");
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("\\[([^\\]]+)]\\(((?:https?|mailto):[^)\\s]+)\\)");
    private static final Pattern AUTO_LINK_PATTERN = Pattern.compile("&lt;((?:https?|mailto):[^\\s]+?)&gt;");
    private static final Pattern HTML_BREAK_PATTERN = Pattern.compile("(?i)&lt;br\\s*/?&gt;");
    private static final Pattern BOLD_ASTERISK_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern BOLD_UNDERSCORE_PATTERN = Pattern.compile("__(.+?)__");
    private static final Pattern ITALIC_ASTERISK_PATTERN = Pattern.compile("(?<!\\w)\\*([^*]+?)\\*(?!\\w)");
    private static final Pattern ITALIC_UNDERSCORE_PATTERN = Pattern.compile("(?<!\\w)_([^_]+?)_(?!\\w)");

    private MarkdownInlineRenderer() {
    }

    static String render(String text, Palette palette) {
        String escaped = HtmlEscaper.escape(text == null ? "" : text);
        escaped = normalizeMalformedFenceMarkers(escaped);

        CodeExtraction codeExtraction = extractInlineCode(escaped);
        String rendered = applyInlineFormatting(codeExtraction.text());

        rendered = renderAnchors(rendered, MARKDOWN_LINK_PATTERN, 1, 2);
        rendered = renderAnchors(rendered, AUTO_LINK_PATTERN, 1, 1);

        return restoreInlineCode(rendered, codeExtraction.codeSegments(), palette);
    }

    private static String applyInlineFormatting(String text) {
        String rendered = HTML_BREAK_PATTERN.matcher(text).replaceAll("<br>");
        rendered = BOLD_ASTERISK_PATTERN.matcher(rendered).replaceAll("<b>$1</b>");
        rendered = BOLD_UNDERSCORE_PATTERN.matcher(rendered).replaceAll("<b>$1</b>");
        rendered = ITALIC_ASTERISK_PATTERN.matcher(rendered).replaceAll("<i>$1</i>");
        return ITALIC_UNDERSCORE_PATTERN.matcher(rendered).replaceAll("<i>$1</i>");
    }

    private static String normalizeMalformedFenceMarkers(String text) {
        String normalized = text.replaceAll("\\*\\*```\\s*([A-Za-z0-9_+\\-]+)\\s*\\*\\*", "$1");
        normalized = normalized.replace("```", "");
        normalized = normalized.replaceAll("\\*\\*\\s*<br>", "<br>");
        normalized = normalized.replaceAll("<br>\\s*\\*\\*", "<br>");
        return normalized.replaceAll("(?<=\\s)\\*\\*(?=\\s*(?:<br>|\\||$))", "");
    }

    private static CodeExtraction extractInlineCode(String text) {
        List<String> codeSegments = new ArrayList<>();
        String withTokens = INLINE_CODE_PATTERN.matcher(text).replaceAll(matchResult -> {
            codeSegments.add(matchResult.group(1));
            return inlineCodeToken(codeSegments.size() - 1);
        });

        return new CodeExtraction(withTokens, List.copyOf(codeSegments));
    }

    private static String restoreInlineCode(String text, List<String> codeSegments, Palette palette) {
        return INLINE_CODE_TOKEN_PATTERN.matcher(text).replaceAll(matchResult -> {
            int index = Integer.parseInt(matchResult.group(1));
            if (index < 0 || index >= codeSegments.size()) {
                return matchResult.group();
            }

            return Matcher.quoteReplacement(inlineCodeHtml(codeSegments.get(index), palette));
        });
    }

    private static String renderAnchors(String text, Pattern pattern, int labelGroup, int hrefGroup) {
        return pattern.matcher(text).replaceAll(matchResult -> Matcher.quoteReplacement(
                "<a href=\"%s\">%s</a>".formatted(
                        matchResult.group(hrefGroup),
                        matchResult.group(labelGroup)
                )));
    }

    private static String inlineCodeToken(int index) {
        return "@@INLINE_CODE_%d@@".formatted(index);
    }

    private static String inlineCodeHtml(String code, Palette palette) {
        return "<code style=\"background-color: %s; border: 1px solid %s; padding: 1px 4px;\"><font face=\"%s\" size=\"3\" color=\"%s\">%s</font></code>"
                .formatted(
                        palette.inlineCodeBg(),
                        palette.codeBorder(),
                        palette.monoFontFamilyAttr(),
                        palette.codeText(),
                        code
                );
    }

    private record CodeExtraction(String text, List<String> codeSegments) {
    }
}
