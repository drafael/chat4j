package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.chat.content.ExternalLinkSupport;
import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

final class AssistantSourceFormatter {

    private static final Pattern SOURCE_URL_PATTERN = Pattern.compile("(?:\\[[^]]+])?\\(<(https?://[^>\\s]+)>\\)|<(https?://[^>\\s]+)>|(?:\\[[^]]+])?\\((https?://(?:[^\\s()<>]|\\([^\\s()<>]*\\))+)\\)|(https?://(?:[^\\s()<>]|\\([^\\s()<>]*\\))+)");
    private static final Pattern SOURCE_REFERENCE_LINE_PATTERN = Pattern.compile("(?m)^\\s*(?:[-*]\\s*)?\\[\\d+]\\s*(?:\\([^)]*https?://[^)]*\\)|.*https?://\\S+)");

    private AssistantSourceFormatter() {
    }

    static String mergeWebSearchActivityWithAnswerSources(
            boolean webSearchEnabled,
            String assistantText,
            String existingActivity,
            List<CitationRef> citations
    ) {
        if (!webSearchEnabled) {
            return existingActivity;
        }

        String sourceActivity = citationSourceLines(citations);
        if (StringUtils.isBlank(sourceActivity)) {
            sourceActivity = extractWebSearchSourcesFromAssistantText(assistantText);
        }
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

    static String escapeMarkdownLinkLabel(String value) {
        return StringUtils.defaultString(value).replace("[", "\\[").replace("]", "\\]");
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

    private static String extractWebSearchSourcesFromAssistantText(String assistantText) {
        if (StringUtils.isBlank(assistantText)) {
            return "";
        }

        List<String> sourceItems = Arrays.stream(assistantText.split("\\R"))
                .map(SOURCE_URL_PATTERN::matcher)
                .filter(Matcher::find)
                .map(AssistantSourceFormatter::matchedSourceItem)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .limit(10)
                .toList();
        if (sourceItems.isEmpty()) {
            return "";
        }

        StringBuilder sources = new StringBuilder();
        sourceItems.forEach(item -> sources.append("- ").append(item).append("\n"));
        return sources.toString().trim();
    }

    private static String matchedSourceItem(Matcher matcher) {
        String match = StringUtils.trimToEmpty(matcher.group());
        return Strings.CI.startsWith(match, "http://") || Strings.CI.startsWith(match, "https://")
                ? markdownLinkDestination(matchedSourceUrl(matcher))
                : match;
    }

    private static String matchedSourceUrl(Matcher matcher) {
        for (int i = 1; i <= matcher.groupCount(); i++) {
            String value = matcher.group(i);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }
}
