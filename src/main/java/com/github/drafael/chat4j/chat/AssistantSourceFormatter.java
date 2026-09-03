package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.chat.content.ExternalLinkSupport;
import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

final class AssistantSourceFormatter {

    private static final Pattern SOURCE_URL_PATTERN = Pattern.compile("(?:\\[[^]]+])?\\(<(https?://[^>\\s]+)>\\)|<(https?://[^>\\s]+)>|(?:\\[[^]]+])?\\((https?://(?:[^\\s()<>]|\\([^\\s()<>]*\\))+)\\)|(https?://(?:[^\\s()<>]|\\([^\\s()<>]*\\))+)");
    private static final Pattern SOURCE_REFERENCE_LINE_PATTERN = Pattern.compile("(?m)^\\s*(?:[-*]\\s*)?\\[\\d+]\\s*(?:\\([^)]*https?://[^)]*\\)|.*https?://\\S+)");

    private AssistantSourceFormatter() {
    }

    static String mergeWebSearchActivityWithAnswerSources(
            boolean webSearchEnabled,
            String existingActivity,
            List<CitationRef> citations
    ) {
        if (!webSearchEnabled) {
            return existingActivity;
        }

        String sourceActivity = citationSourceLines(citations);
        if (StringUtils.isBlank(sourceActivity)) {
            return existingActivity;
        }

        return "%s\n\n**Sources**\n%s".formatted(
                StringUtils.defaultString(existingActivity).trim(),
                sourceActivity
        ).trim();
    }

    static String appendCitationSourcesIfNeeded(String assistantText, List<CitationRef> citations) {
        String text = StringUtils.defaultString(assistantText);
        if (citations == null || citations.isEmpty()
                || SOURCE_REFERENCE_LINE_PATTERN.matcher(text).find()
                || hasSourceSectionWithUrls(text)) {
            return text;
        }

        String sources = citationSourceLines(citations);
        if (StringUtils.isBlank(sources)) {
            return text;
        }

        String answer = text.stripTrailing();
        return StringUtils.isBlank(answer)
                ? "Sources:\n%s".formatted(sources)
                : "%s\n\nSources:\n%s".formatted(answer, sources);
    }

    static List<CitationRef> citationsValidForPartialResponse(String assistantText, List<CitationRef> citations) {
        if (citations == null || citations.isEmpty()) {
            return emptyList();
        }

        String text = StringUtils.defaultString(assistantText);
        return citations.stream()
                .filter(citation -> citation != null)
                .filter(citation -> {
                    boolean hasStart = citation.responseStartIndex() != null;
                    boolean hasEnd = citation.responseEndIndex() != null;
                    return !hasStart && !hasEnd || hasStart && hasEnd && toResponseSpan(citation, text).isPresent();
                })
                .toList();
    }

    static String normalizeResponseCitationMarkers(String assistantText, List<CitationRef> citations) {
        String text = StringUtils.defaultString(assistantText);
        if (text.isEmpty() || citations == null || citations.isEmpty()) {
            return text;
        }

        Map<ResponseSpan, TreeSet<Integer>> numbersBySpan = new TreeMap<>(Comparator
                .comparingInt(ResponseSpan::start)
                .thenComparingInt(ResponseSpan::end));
        citations.stream()
                .filter(AssistantSourceFormatter::hasValidResponseSpanMetadata)
                .filter(citation -> ExternalLinkSupport.isAllowedHttpLink(citation.url()))
                .map(citation -> toResponseSpan(citation, text))
                .flatMap(Optional::stream)
                .forEach(positioned -> numbersBySpan
                        .computeIfAbsent(positioned.span(), ignored -> new TreeSet<>())
                        .add(positioned.number()));
        if (numbersBySpan.isEmpty() || containsAllInlineMarkers(text, numbersBySpan.values())) {
            return text;
        }

        Set<ResponseSpan> conflictingSpans = findConflictingSpans(new ArrayList<>(numbersBySpan.keySet()));
        List<ResponseReplacement> replacements = numbersBySpan.entrySet().stream()
                .filter(entry -> !conflictingSpans.contains(entry.getKey()))
                .map(entry -> new ResponseReplacement(entry.getKey(), entry.getValue()))
                .toList();
        List<ResponseReplacement> mergedReplacements = mergeAdjacentReplacements(replacements);

        StringBuilder normalized = new StringBuilder(text);
        for (int i = mergedReplacements.size() - 1; i >= 0; i--) {
            ResponseReplacement replacement = mergedReplacements.get(i);
            String markers = replacement.numbers().stream()
                    .map(number -> "[%d]".formatted(number))
                    .collect(joining());
            String prefix = replacement.span().start() > 0
                    && !Character.isWhitespace(text.charAt(replacement.span().start() - 1))
                    ? " "
                    : "";
            normalized.replace(replacement.span().start(), replacement.span().end(), prefix + markers);
        }
        return normalized.toString();
    }

    static String escapeMarkdownLinkLabel(String value) {
        return StringUtils.defaultString(value).replace("[", "\\[").replace("]", "\\]");
    }

    private static boolean hasValidResponseSpanMetadata(CitationRef citation) {
        return citation != null
                && citation.number() > 0
                && citation.kind() == CitationKind.WEB
                && citation.responseStartIndex() != null
                && citation.responseEndIndex() != null;
    }

    private static Optional<PositionedCitation> toResponseSpan(CitationRef citation, String text) {
        long start = citation.responseStartIndex();
        long end = citation.responseEndIndex();
        if (start < 0 || end <= start || end > text.length()) {
            return Optional.empty();
        }

        int startIndex = Math.toIntExact(start);
        int endIndex = Math.toIntExact(end);
        if (!isCharacterBoundary(text, startIndex) || !isCharacterBoundary(text, endIndex)) {
            return Optional.empty();
        }
        return Optional.of(new PositionedCitation(
                new ResponseSpan(startIndex, endIndex),
                citation.number()
        ));
    }

    private static boolean isCharacterBoundary(String text, int index) {
        return index <= 0
                || index >= text.length()
                || !Character.isHighSurrogate(text.charAt(index - 1))
                || !Character.isLowSurrogate(text.charAt(index));
    }

    private static boolean containsAllInlineMarkers(String text, Iterable<TreeSet<Integer>> groupedNumbers) {
        String inlineText = answerBeforeSources(text);
        Map<Integer, Integer> requiredOccurrences = new TreeMap<>();
        groupedNumbers.forEach(numbers -> numbers.forEach(number -> requiredOccurrences.merge(number, 1, Integer::sum)));
        return requiredOccurrences.entrySet().stream().allMatch(entry -> {
            Pattern markerPattern = Pattern.compile("(?<!\\d)\\[%d](?!\\d)".formatted(entry.getKey()));
            return markerPattern.matcher(inlineText).results().count() >= entry.getValue();
        });
    }

    private static String answerBeforeSources(String text) {
        int offset = 0;
        for (String line : text.split("\\R", -1)) {
            if (Strings.CI.equals(normalizeHeadingLine(line), "sources")) {
                return text.substring(0, offset);
            }
            offset += line.length() + 1;
        }
        return text;
    }

    private static Set<ResponseSpan> findConflictingSpans(List<ResponseSpan> spans) {
        Set<ResponseSpan> conflicts = new HashSet<>();
        for (int leftIndex = 0; leftIndex < spans.size(); leftIndex++) {
            ResponseSpan left = spans.get(leftIndex);
            for (int rightIndex = leftIndex + 1; rightIndex < spans.size(); rightIndex++) {
                ResponseSpan right = spans.get(rightIndex);
                if (left.start() < right.end() && right.start() < left.end()) {
                    conflicts.add(left);
                    conflicts.add(right);
                }
            }
        }
        return conflicts;
    }

    private static List<ResponseReplacement> mergeAdjacentReplacements(List<ResponseReplacement> replacements) {
        List<ResponseReplacement> merged = new ArrayList<>();
        replacements.forEach(replacement -> {
            if (merged.isEmpty()) {
                merged.add(replacement);
                return;
            }
            ResponseReplacement previous = merged.getLast();
            if (previous.span().end() != replacement.span().start()) {
                merged.add(replacement);
                return;
            }
            TreeSet<Integer> numbers = new TreeSet<>(previous.numbers());
            numbers.addAll(replacement.numbers());
            merged.set(
                    merged.size() - 1,
                    new ResponseReplacement(
                            new ResponseSpan(previous.span().start(), replacement.span().end()),
                            numbers
                    )
            );
        });
        return List.copyOf(merged);
    }

    private static boolean hasSourceSectionWithUrls(String text) {
        boolean inSources = false;
        for (String line : text.split("\\R")) {
            String normalizedLine = normalizeHeadingLine(line);
            if (Strings.CI.equals(normalizedLine, "sources")) {
                inSources = true;
                continue;
            }
            if (inSources && isMarkdownHeadingLine(line)) {
                inSources = false;
            }
            if (inSources && SOURCE_URL_PATTERN.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMarkdownHeadingLine(String line) {
        String trimmed = StringUtils.trimToEmpty(line);
        return trimmed.startsWith("#") || trimmed.matches("\\*\\*.+\\*\\*:?");
    }

    private static String normalizeHeadingLine(String line) {
        String heading = StringUtils.trimToEmpty(line).replaceFirst("^#+\\s*", "");
        heading = Strings.CS.removeEnd(heading, ":").trim();
        heading = Strings.CS.removeEnd(Strings.CS.removeStart(heading, "**"), "**").trim();
        heading = Strings.CS.removeEnd(heading, ":").trim();
        return heading;
    }

    private static String citationSourceLines(List<CitationRef> citations) {
        if (citations == null || citations.isEmpty()) {
            return "";
        }

        return citations.stream()
                .filter(citation -> citation != null && citation.number() > 0)
                .filter(citation -> citation.kind() == CitationKind.WEB)
                .filter(citation -> ExternalLinkSupport.isAllowedHttpLink(citation.url()))
                .collect(toMap(
                        CitationRef::number,
                        AssistantSourceFormatter::citationSourceLine,
                        (existing, replacement) -> existing,
                        TreeMap::new
                ))
                .values()
                .stream()
                .collect(joining("\n"));
    }

    private static String citationSourceLine(CitationRef citation) {
        return "[%d] [%s](%s)".formatted(
                citation.number(),
                escapeMarkdownLinkLabel(citationSourceLabel(citation)),
                markdownLinkDestination(citation.url())
        );
    }

    private static String markdownLinkDestination(String url) {
        return "<%s>".formatted(StringUtils.defaultString(url).replace(">", "%3E"));
    }

    private static String citationSourceLabel(CitationRef citation) {
        String title = StringUtils.trimToEmpty(citation.displayTitle()).replaceAll("\\s+", " ");
        if (StringUtils.isNotBlank(title) && !Strings.CS.equals(title, citation.url())) {
            return title;
        }
        return sourceDomain(citation.url());
    }

    private static String sourceDomain(String url) {
        try {
            String host = URI.create(url).getHost();
            return StringUtils.defaultIfBlank(Strings.CS.removeStart(host, "www."), url);
        } catch (Exception e) {
            return url;
        }
    }

    private record ResponseSpan(int start, int end) {
    }

    private record PositionedCitation(ResponseSpan span, int number) {
    }

    private record ResponseReplacement(ResponseSpan span, TreeSet<Integer> numbers) {
        private ResponseReplacement {
            numbers = new TreeSet<>(numbers);
        }
    }
}
