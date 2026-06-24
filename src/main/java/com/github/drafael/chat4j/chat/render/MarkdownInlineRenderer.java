package com.github.drafael.chat4j.chat.render;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MarkdownInlineRenderer {

    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`]+?)`");
    private static final Pattern INLINE_CODE_TOKEN_PATTERN = Pattern.compile("@@INLINE_CODE_(\\d+)@@");
    private static final Pattern DISPLAY_BRACKET_MATH_PATTERN = Pattern.compile("(?s)\\\\\\[(.+?)\\\\\\]");
    private static final Pattern INLINE_PAREN_MATH_PATTERN = Pattern.compile("\\\\\\((.+?)\\\\\\)");
    private static final Pattern MATH_TOKEN_PATTERN = Pattern.compile("@@MATH_(\\d+)@@");
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile(
            "\\[((?:\\\\.|[^\\\\\\]])+)\\]\\((?:&lt;((?:https?|mailto):[^\\s]+)&gt;|((?:https?|mailto):[^)\\s]+))\\)"
    );
    private static final Pattern MARKDOWN_LINK_TOKEN_PATTERN = Pattern.compile("@@MARKDOWN_LINK_(\\d+)@@");
    private static final Pattern ESCAPED_LINK_LABEL_CHAR_PATTERN = Pattern.compile("\\\\([\\[\\]\\\\])");
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
        LinkExtraction linkExtraction = extractMarkdownLinks(codeExtraction.text());
        MathExtraction mathExtraction = extractMathSegments(linkExtraction.text());
        String rendered = applyInlineFormatting(mathExtraction.text());

        rendered = restoreMarkdownLinks(rendered, linkExtraction.linkSegments());
        rendered = renderAnchors(rendered, MARKDOWN_LINK_PATTERN, 1, 2);
        rendered = renderAnchors(rendered, AUTO_LINK_PATTERN, 1, 1);
        rendered = restoreMathSegments(rendered, mathExtraction.mathSegments(), palette);

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

    private static LinkExtraction extractMarkdownLinks(String text) {
        List<String> linkSegments = new ArrayList<>();
        String withTokens = MARKDOWN_LINK_PATTERN.matcher(text).replaceAll(matchResult -> {
            linkSegments.add(matchResult.group());
            return markdownLinkToken(linkSegments.size() - 1);
        });

        return new LinkExtraction(withTokens, List.copyOf(linkSegments));
    }

    private static String restoreMarkdownLinks(String text, List<String> linkSegments) {
        return MARKDOWN_LINK_TOKEN_PATTERN.matcher(text).replaceAll(matchResult -> {
            int index = Integer.parseInt(matchResult.group(1));
            if (index < 0 || index >= linkSegments.size()) {
                return matchResult.group();
            }

            return Matcher.quoteReplacement(linkSegments.get(index));
        });
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

    private static MathExtraction extractMathSegments(String text) {
        List<String> mathSegments = new ArrayList<>();

        String withDollarMathTokens = extractDollarMathSegments(text, mathSegments);

        String withDisplayBracketMathTokens = DISPLAY_BRACKET_MATH_PATTERN.matcher(withDollarMathTokens).replaceAll(matchResult -> {
            mathSegments.add(matchResult.group());
            return mathToken(mathSegments.size() - 1);
        });

        String withAllMathTokens = INLINE_PAREN_MATH_PATTERN.matcher(withDisplayBracketMathTokens).replaceAll(matchResult -> {
            mathSegments.add(matchResult.group());
            return mathToken(mathSegments.size() - 1);
        });

        return new MathExtraction(withAllMathTokens, List.copyOf(mathSegments));
    }

    private static String extractDollarMathSegments(String text, List<String> mathSegments) {
        StringBuilder rendered = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            int open = nextUnescapedDollar(text, index);
            if (open < 0) {
                rendered.append(text, index, text.length());
                break;
            }

            rendered.append(text, index, open);
            boolean display = open + 1 < text.length() && text.charAt(open + 1) == '$';
            String delimiter = display ? "$$" : "$";
            int contentStart = open + delimiter.length();
            int close = findDollarMathClose(text, contentStart, display);
            if (close < 0 || (!display && isLikelyCurrencyOrPunctuationMath(text, open, contentStart, close))) {
                rendered.append(text.charAt(open));
                index = open + 1;
                continue;
            }

            String math = text.substring(open, close + delimiter.length());
            mathSegments.add(math);
            rendered.append(mathToken(mathSegments.size() - 1));
            index = close + delimiter.length();
        }
        return rendered.toString();
    }

    private static int nextUnescapedDollar(String text, int start) {
        for (int index = start; index < text.length(); index++) {
            if (text.charAt(index) == '$' && !isEscaped(text, index)) {
                return index;
            }
        }
        return -1;
    }

    private static int findDollarMathClose(String text, int start, boolean display) {
        int braceDepth = 0;
        for (int index = start; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\\') {
                index++;
                continue;
            }
            if (current == '{') {
                braceDepth++;
                continue;
            }
            if (current == '}') {
                braceDepth = Math.max(0, braceDepth - 1);
                continue;
            }
            if (braceDepth == 0 && current == '$' && !isEscaped(text, index)) {
                if (display && index + 1 < text.length() && text.charAt(index + 1) == '$') {
                    return index;
                }
                if (!display && (index + 1 >= text.length() || text.charAt(index + 1) != '$')) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static boolean isEscaped(String text, int index) {
        int slashCount = 0;
        for (int cursor = index - 1; cursor >= 0 && text.charAt(cursor) == '\\'; cursor--) {
            slashCount++;
        }
        return slashCount % 2 == 1;
    }

    private static boolean isLikelyCurrencyOrPunctuationMath(String text, int dollarIndex, int contentStart, int close) {
        if (dollarIndex + 1 >= text.length()) {
            return true;
        }
        char next = text.charAt(dollarIndex + 1);
        if (next == '$' || next == '.' || next == ',') {
            return true;
        }
        if (!Character.isDigit(next)) {
            return false;
        }
        return !MarkdownMathHeuristics.isNumericLeadingMathContent(text.substring(contentStart, close));
    }

    private static String restoreMathSegments(String text, List<String> mathSegments, Palette palette) {
        return MATH_TOKEN_PATTERN.matcher(text).replaceAll(matchResult -> {
            int index = Integer.parseInt(matchResult.group(1));
            if (index < 0 || index >= mathSegments.size()) {
                return matchResult.group();
            }

            return Matcher.quoteReplacement(inlineMathHtml(mathSegments.get(index), palette));
        });
    }

    private static String renderAnchors(String text, Pattern pattern, int labelGroup, int hrefGroup) {
        return pattern.matcher(text).replaceAll(matchResult -> {
            String label = matchResult.group(labelGroup);
            String href = matchResult.group(hrefGroup);
            if (href == null && matchResult.groupCount() > hrefGroup) {
                href = matchResult.group(hrefGroup + 1);
            }
            String renderedLabel = label.matches("\\d+")
                    ? "[%s]".formatted(label)
                    : unescapeLinkLabel(label);
            return Matcher.quoteReplacement("<a href=\"%s\">%s</a>".formatted(href, renderedLabel));
        });
    }

    private static String unescapeLinkLabel(String label) {
        return ESCAPED_LINK_LABEL_CHAR_PATTERN.matcher(label).replaceAll("$1");
    }

    private static String mathToken(int index) {
        return "@@MATH_%d@@".formatted(index);
    }

    private static String inlineCodeToken(int index) {
        return "@@INLINE_CODE_%d@@".formatted(index);
    }

    private static String markdownLinkToken(int index) {
        return "@@MARKDOWN_LINK_%d@@".formatted(index);
    }

    private static String inlineCodeHtml(String code, Palette palette) {
        int codeFontSize = CodeFontResolver.resolveCodeFontSize();
        return "<code style=\"background-color: %s; border: 1px solid %s; padding: 1px 4px; font-size: %dpx;\"><font face=\"%s\" color=\"%s\">%s</font></code>"
                .formatted(
                        palette.inlineCodeBg(),
                        palette.codeBorder(),
                        codeFontSize,
                        palette.monoFontFamilyAttr(),
                        palette.codeText(),
                        code
                );
    }

    private static String inlineMathHtml(String math, Palette palette) {
        int codeFontSize = CodeFontResolver.resolveCodeFontSize();
        return "<code class=\"md-latex-inline\" style=\"font-size: %dpx;\"><font face=\"%s\" color=\"%s\">%s</font></code>"
                .formatted(
                        codeFontSize,
                        palette.monoFontFamilyAttr(),
                        palette.codeText(),
                        math
                );
    }

    private record CodeExtraction(String text, List<String> codeSegments) {
        @Override
        public String toString() {
            return "CodeExtraction[text=<masked>, codeSegments=%d]"
                    .formatted(codeSegments == null ? 0 : codeSegments.size());
        }
    }

    private record LinkExtraction(String text, List<String> linkSegments) {
        @Override
        public String toString() {
            return "LinkExtraction[text=<masked>, linkSegments=%d]"
                    .formatted(linkSegments == null ? 0 : linkSegments.size());
        }
    }

    private record MathExtraction(String text, List<String> mathSegments) {
        @Override
        public String toString() {
            return "MathExtraction[text=<masked>, mathSegments=%d]"
                    .formatted(mathSegments == null ? 0 : mathSegments.size());
        }
    }
}
