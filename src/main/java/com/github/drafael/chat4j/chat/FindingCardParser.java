package com.github.drafael.chat4j.chat;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FindingCardParser {

    private static final String FINDINGS_MARKER = "findings";
    private static final Pattern FINDING_TITLE_PATTERN = Pattern.compile("^\\s*(?:\\[?((?:P\\d+)|(?:HIGH)|(?:MEDIUM)|(?:LOW))\\]?)[\\s:—–-]+(.+?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE_REFERENCE_PATTERN = Pattern.compile("(?m)(?:^|\\s)(/?(?:[\\w .-]+/)*[\\w .-]+\\.[A-Za-z0-9]+(?::\\d+(?:[-–]\\d+)?)?)\\s*(?:↗)?\\s*$");

    private FindingCardParser() {
    }

    static List<FindingCardPanel.Finding> parse(String text) {
        if (StringUtils.isBlank(text)) {
            return List.of();
        }

        if (!containsFindingsMarker(text)) {
            return List.of();
        }

        List<FindingCardPanel.Finding> findings = new ArrayList<>();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        PendingFinding pending = null;

        for (String line : lines) {
            Matcher titleMatcher = FINDING_TITLE_PATTERN.matcher(stripMarkdownListPrefix(line));
            if (titleMatcher.matches()) {
                if (pending != null) {
                    findings.add(pending.toFinding());
                }
                pending = new PendingFinding(normalizeSeverity(titleMatcher.group(1)), cleanupInlineMarkdown(titleMatcher.group(2)));
                continue;
            }

            if (pending == null) {
                continue;
            }

            if (StringUtils.isBlank(line)) {
                continue;
            }

            Matcher fileMatcher = FILE_REFERENCE_PATTERN.matcher(line.trim());
            if (fileMatcher.find()) {
                pending.fileReference = cleanupInlineMarkdown(fileMatcher.group(1));
                continue;
            }

            pending.bodyLines.add(cleanupInlineMarkdown(line.trim()));
        }

        if (pending != null) {
            findings.add(pending.toFinding());
        }

        return findings.stream()
                .filter(finding -> StringUtils.isNotBlank(finding.title()))
                .toList();
    }

    private static boolean containsFindingsMarker(String text) {
        return text.lines()
                .map(FindingCardParser::cleanupInlineMarkdown)
                .map(StringUtils::lowerCase)
                .anyMatch(line -> StringUtils.equals(line, FINDINGS_MARKER)
                        || StringUtils.startsWith(line, FINDINGS_MARKER + ":"));
    }

    private static String stripMarkdownListPrefix(String line) {
        return StringUtils.defaultString(line)
                .replaceFirst("^\\s*[-*]\\s+", "")
                .replaceFirst("^\\s*\\d+[.)]\\s+", "");
    }

    private static String normalizeSeverity(String severity) {
        String normalized = StringUtils.upperCase(StringUtils.trimToEmpty(severity));
        return switch (normalized) {
            case "HIGH" -> "P1";
            case "MEDIUM" -> "P2";
            case "LOW" -> "P3";
            default -> normalized;
        };
    }

    private static String cleanupInlineMarkdown(String value) {
        return StringUtils.defaultString(value)
                .replace("`", "")
                .replaceAll("^#+\\s*", "")
                .trim();
    }

    private static final class PendingFinding {
        private final String severity;
        private final String title;
        private final List<String> bodyLines = new ArrayList<>();
        private String fileReference = "";

        private PendingFinding(String severity, String title) {
            this.severity = severity;
            this.title = title;
        }

        private FindingCardPanel.Finding toFinding() {
            return new FindingCardPanel.Finding(
                    severity,
                    title,
                    String.join("\n", bodyLines),
                    fileReference
            );
        }
    }
}
