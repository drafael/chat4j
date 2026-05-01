package com.github.drafael.chat4j.chat;

import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SourceReferenceLinkifier {

    private static final Pattern SOURCE_LINE_PATTERN = Pattern.compile(
            "^(\\s*(?:[-*]\\s*)?)\\[(\\d+)](?::)?\\s+((?:https?)://\\S+)(.*)$"
    );
    private static final Pattern CITATION_MARKER_PATTERN = Pattern.compile("\\[(\\d+)](?!\\()");

    private SourceReferenceLinkifier() {
    }

    static String linkify(String markdown) {
        if (StringUtils.isBlank(markdown)) {
            return markdown;
        }

        String[] lines = markdown.split("\\n", -1);
        Map<String, String> sourcesByNumber = collectSources(lines);
        if (sourcesByNumber.isEmpty()) {
            return markdown;
        }

        StringBuilder linked = new StringBuilder(markdown.length() + sourcesByNumber.size() * 16);
        boolean inCodeBlock = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean codeFenceLine = line.trim().startsWith("```");
            if (codeFenceLine) {
                inCodeBlock = !inCodeBlock;
                appendLine(linked, line, i, lines.length);
                continue;
            }

            if (inCodeBlock) {
                appendLine(linked, line, i, lines.length);
                continue;
            }

            SourceLine sourceLine = parseSourceLine(line);
            if (sourceLine != null && sourcesByNumber.containsKey(sourceLine.number())) {
                appendLine(linked, renderSourceLine(sourceLine), i, lines.length);
                continue;
            }

            appendLine(linked, linkCitationMarkers(line, sourcesByNumber), i, lines.length);
        }
        return linked.toString();
    }

    private static Map<String, String> collectSources(String[] lines) {
        Map<String, String> sourcesByNumber = new LinkedHashMap<>();
        boolean inCodeBlock = false;
        for (String line : lines) {
            if (line.trim().startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                continue;
            }
            if (inCodeBlock) {
                continue;
            }

            SourceLine sourceLine = parseSourceLine(line);
            if (sourceLine != null) {
                sourcesByNumber.putIfAbsent(sourceLine.number(), sourceLine.url());
            }
        }
        return sourcesByNumber;
    }

    private static SourceLine parseSourceLine(String line) {
        Matcher matcher = SOURCE_LINE_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return null;
        }

        String rawUrl = matcher.group(3);
        String url = trimTrailingUrlPunctuation(rawUrl);
        String suffix = rawUrl.substring(url.length()) + matcher.group(4);
        return new SourceLine(matcher.group(1), matcher.group(2), url, suffix);
    }

    private static String renderSourceLine(SourceLine sourceLine) {
        return "%s[%s](%s) <%s>%s".formatted(
                sourceLine.prefix(),
                sourceLine.number(),
                sourceLine.url(),
                sourceLine.url(),
                sourceLine.suffix()
        );
    }

    private static String linkCitationMarkers(String line, Map<String, String> sourcesByNumber) {
        Matcher matcher = CITATION_MARKER_PATTERN.matcher(line);
        StringBuilder linked = new StringBuilder(line.length());
        while (matcher.find()) {
            String sourceNumber = matcher.group(1);
            String sourceUrl = sourcesByNumber.get(sourceNumber);
            if (sourceUrl == null) {
                matcher.appendReplacement(linked, Matcher.quoteReplacement(matcher.group()));
                continue;
            }

            String replacement = " [%s](%s)".formatted(sourceNumber, sourceUrl);
            matcher.appendReplacement(linked, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(linked);
        return linked.toString();
    }

    private static void appendLine(StringBuilder linked, String line, int index, int lineCount) {
        linked.append(line);
        if (index < lineCount - 1) {
            linked.append('\n');
        }
    }

    private static String trimTrailingUrlPunctuation(String url) {
        int end = url.length();
        while (end > 0 && isTrailingUrlPunctuation(url.charAt(end - 1))) {
            end--;
        }
        return url.substring(0, end);
    }

    private static boolean isTrailingUrlPunctuation(char value) {
        return value == '.' || value == ',' || value == ';' || value == ':';
    }

    private record SourceLine(String prefix, String number, String url, String suffix) {
    }
}
