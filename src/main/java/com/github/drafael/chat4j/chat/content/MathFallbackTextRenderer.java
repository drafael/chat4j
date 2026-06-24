package com.github.drafael.chat4j.chat.content;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MathFallbackTextRenderer {

    private static final KatexMathRenderer KATEX_RENDERER = KatexMathRenderer.instance();
    private static final Pattern TEXT_COMMAND_PATTERN = Pattern.compile("\\\\(?:text|mathrm|operatorname|ce)\\s*\\{([^{}]*)}");
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("([_^])(?:\\{([^{}]+)}|([A-Za-z0-9+\\-=()]))");
    private static final Map<Character, Character> SUBSCRIPT_CHARS = Map.ofEntries(
            Map.entry('0', '₀'),
            Map.entry('1', '₁'),
            Map.entry('2', '₂'),
            Map.entry('3', '₃'),
            Map.entry('4', '₄'),
            Map.entry('5', '₅'),
            Map.entry('6', '₆'),
            Map.entry('7', '₇'),
            Map.entry('8', '₈'),
            Map.entry('9', '₉'),
            Map.entry('+', '₊'),
            Map.entry('-', '₋'),
            Map.entry('=', '₌'),
            Map.entry('(', '₍'),
            Map.entry(')', '₎')
    );
    private static final Map<Character, Character> SUPERSCRIPT_CHARS = Map.ofEntries(
            Map.entry('0', '⁰'),
            Map.entry('1', '¹'),
            Map.entry('2', '²'),
            Map.entry('3', '³'),
            Map.entry('4', '⁴'),
            Map.entry('5', '⁵'),
            Map.entry('6', '⁶'),
            Map.entry('7', '⁷'),
            Map.entry('8', '⁸'),
            Map.entry('9', '⁹'),
            Map.entry('+', '⁺'),
            Map.entry('-', '⁻'),
            Map.entry('=', '⁼'),
            Map.entry('(', '⁽'),
            Map.entry(')', '⁾')
    );

    private MathFallbackTextRenderer() {
    }

    public static String replaceFallbackNodesWithReadableText(String html) {
        String safeHtml = StringUtils.defaultString(html);
        if (!safeHtml.contains("md-latex-")) {
            return safeHtml;
        }

        Document document = Jsoup.parse(safeHtml);
        document.outputSettings().prettyPrint(false);

        document.select("code.md-latex-inline").forEach(code -> {
            Element replacement = new Element("span")
                    .addClass("md-latex-text-fallback");
            replacement.text(readableText(code.text(), false));
            code.replaceWith(replacement);
        });

        document.select("table.md-latex-block").forEach(table -> {
            Element pre = table.selectFirst("pre");
            if (pre == null) {
                return;
            }
            Element replacement = new Element("div")
                    .addClass("md-latex-display-fallback");
            replacement.text(readableText(pre.text(), true));
            table.replaceWith(replacement);
        });

        return document.outerHtml();
    }

    public static String readableText(String source, boolean displayMode) {
        return KATEX_RENDERER.render(source, displayMode)
                .map(MathFallbackTextRenderer::katexHtmlText)
                .filter(StringUtils::isNotBlank)
                .orElseGet(() -> plainLatexText(source));
    }

    private static String katexHtmlText(String html) {
        Document document = Jsoup.parseBodyFragment(html);
        Element katexHtml = document.selectFirst(".katex-html");
        return katexHtml == null ? "" : StringUtils.trimToEmpty(katexHtml.text());
    }

    private static String plainLatexText(String source) {
        String text = stripMathDelimiters(source);
        text = replaceTextCommands(text);
        text = replaceScripts(text);
        text = text.replace("\\\\", " ")
                .replace("\\cdot", "·")
                .replace("\\times", "×")
                .replace("\\pm", "±")
                .replace("\\to", "→")
                .replace("\\rightarrow", "→")
                .replace("\\leftarrow", "←")
                .replaceAll("\\\\[,;! ]", "")
                .replaceAll("\\\\[A-Za-z]+", "")
                .replace('{', ' ')
                .replace('}', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return StringUtils.defaultIfBlank(text, StringUtils.defaultString(source));
    }

    private static String stripMathDelimiters(String source) {
        String text = StringUtils.trimToEmpty(source);
        if (text.length() >= 4 && text.startsWith("$$") && text.endsWith("$$")) {
            return text.substring(2, text.length() - 2).trim();
        }
        if (text.length() >= 4 && text.startsWith("\\[") && text.endsWith("\\]")) {
            return text.substring(2, text.length() - 2).trim();
        }
        if (text.length() >= 4 && text.startsWith("\\(") && text.endsWith("\\)")) {
            return text.substring(2, text.length() - 2).trim();
        }
        if (text.length() >= 2 && text.startsWith("$") && text.endsWith("$")) {
            return text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    private static String replaceTextCommands(String text) {
        Matcher matcher = TEXT_COMMAND_PATTERN.matcher(text);
        StringBuilder replaced = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(replaced, Matcher.quoteReplacement(matcher.group(1)));
        }
        matcher.appendTail(replaced);
        return replaced.toString();
    }

    private static String replaceScripts(String text) {
        Matcher matcher = SCRIPT_PATTERN.matcher(text);
        StringBuilder replaced = new StringBuilder();
        while (matcher.find()) {
            String script = matcher.group(2) == null ? matcher.group(3) : matcher.group(2);
            Map<Character, Character> characterMap = "_".equals(matcher.group(1)) ? SUBSCRIPT_CHARS : SUPERSCRIPT_CHARS;
            matcher.appendReplacement(replaced, Matcher.quoteReplacement(scriptText(script, characterMap)));
        }
        matcher.appendTail(replaced);
        return replaced.toString();
    }

    private static String scriptText(String script, Map<Character, Character> characterMap) {
        StringBuilder text = new StringBuilder(script.length());
        for (int index = 0; index < script.length(); index++) {
            char current = script.charAt(index);
            text.append(characterMap.getOrDefault(current, current));
        }
        return text.toString();
    }
}
