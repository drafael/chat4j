package com.github.drafael.chat4j.chat;

import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WebSearchActivityNormalizer {

    private static final Pattern SOURCE_URL_PATTERN = Pattern.compile("(?:\\[[^]]+])?\\(?(https?://[^\\s)<>]+)\\)?|<?(https?://[^\\s)<>]+)>?");

    private WebSearchActivityNormalizer() {
    }

    static String normalize(String activity) {
        if (StringUtils.isBlank(activity)) {
            return "";
        }

        LinkedHashSet<String> searched = new LinkedHashSet<>();
        LinkedHashMap<String, String> sources = new LinkedHashMap<>();
        WebActivitySection section = WebActivitySection.NONE;
        for (String rawLine : activity.split("\\R")) {
            String line = rawLine.trim();
            WebActivitySection heading = sectionFromHeading(line);
            if (heading != WebActivitySection.NONE) {
                section = heading;
                continue;
            }
            if (StringUtils.isBlank(line)) {
                continue;
            }

            addLine(searched, sources, section, line);
        }

        removeNativePlaceholderWhenRealSourcesExist(sources);
        return render(searched, sources);
    }

    private static WebActivitySection sectionFromHeading(String line) {
        String heading = line.replaceFirst("^#+\\s*", "").trim();
        heading = StringUtils.removeEnd(heading, ":").trim();
        heading = StringUtils.removeEnd(StringUtils.removeStart(heading, "**"), "**").trim();
        return switch (heading.toLowerCase()) {
            case "searched" -> {
                yield WebActivitySection.SEARCHED;
            }
            // Older metadata used separate UI sections: "Visited sources" for provider search results and
            // "Browsed pages" for pages Chat4J fetched. New UI merges both into one Sources section.
            case "sources", "visited sources", "browsed pages" -> {
                yield WebActivitySection.SOURCES;
            }
            default -> {
                yield WebActivitySection.NONE;
            }
        };
    }

    private static void addLine(
            LinkedHashSet<String> searched,
            LinkedHashMap<String, String> sources,
            WebActivitySection section,
            String line
    ) {
        String item = stripListMarker(line);
        if (StringUtils.isBlank(item)) {
            return;
        }

        switch (section) {
            case SEARCHED -> {
                searched.add(item);
            }
            case SOURCES -> {
                sources.putIfAbsent(dedupeKey(item), "- %s".formatted(item));
            }
            case NONE -> {
                // Ignore stray markdown outside known sections.
            }
        }
    }

    private static String stripListMarker(String line) {
        String item = line.replaceFirst("^[-*]\\s+", "");
        item = item.replaceFirst("^\\d+\\.\\s+", "");
        return item.trim();
    }

    private static String dedupeKey(String item) {
        Matcher matcher = SOURCE_URL_PATTERN.matcher(item);
        if (matcher.find()) {
            String url = normalizeUrl(StringUtils.defaultIfBlank(matcher.group(1), matcher.group(2)));
            return "url:%s".formatted(url);
        }
        return "text:%s".formatted(item.toLowerCase());
    }

    private static String normalizeUrl(String url) {
        String normalized = StringUtils.defaultString(url).replaceAll("[.,;:]+$", "");
        while (normalized.endsWith("/") || normalized.endsWith("#")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static void removeNativePlaceholderWhenRealSourcesExist(LinkedHashMap<String, String> sources) {
        boolean hasRealSourceUrl = sources.keySet().stream().anyMatch(key -> key.startsWith("url:"));
        if (hasRealSourceUrl) {
            sources.entrySet().removeIf(entry -> !entry.getKey().startsWith("url:")
                    && entry.getValue().contains("Native web search is handled"));
        }
    }

    private static String render(LinkedHashSet<String> searched, LinkedHashMap<String, String> sources) {
        StringBuilder normalized = new StringBuilder();
        if (!searched.isEmpty()) {
            normalized.append("**Searched**\n");
            searched.forEach(query -> normalized.append("- ").append(query).append("\n"));
        }
        appendSection(normalized, "Sources", sources);
        return normalized.toString().trim();
    }

    private static void appendSection(StringBuilder normalized, String title, LinkedHashMap<String, String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        trimTrailingWhitespace(normalized);
        if (normalized.length() > 0) {
            normalized.append("\n\n");
        }
        normalized.append("**").append(title).append("**\n");
        lines.values().forEach(line -> normalized.append(line).append("\n"));
    }

    private static void trimTrailingWhitespace(StringBuilder value) {
        while (!value.isEmpty() && Character.isWhitespace(value.charAt(value.length() - 1))) {
            value.setLength(value.length() - 1);
        }
    }

    private enum WebActivitySection {
        NONE,
        SEARCHED,
        SOURCES
    }
}
