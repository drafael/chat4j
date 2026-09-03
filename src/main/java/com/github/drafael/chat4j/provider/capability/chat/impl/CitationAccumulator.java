package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.content.CitationRef;
import org.apache.commons.lang3.StringUtils;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class CitationAccumulator {

    private final Map<String, CitationRef> citationsByKey = new LinkedHashMap<>();
    private final Set<String> emittedOccurrenceKeys = new HashSet<>();

    CitationRef add(CitationRef citation) {
        if (citation == null) {
            return null;
        }

        String key = dedupeKey(citation);
        CitationRef existing = citationsByKey.get(key);
        return existing == null ? addWithKey(key, citation) : existing;
    }

    Optional<CitationRef> addNew(CitationRef citation) {
        if (citation == null) {
            return Optional.empty();
        }

        String sourceKey = dedupeKey(citation);
        CitationRef existing = citationsByKey.get(sourceKey);
        CitationRef numbered = existing == null
                ? addWithKey(sourceKey, citation)
                : citation.withNumber(existing.number());
        return emittedOccurrenceKeys.add(occurrenceKey(sourceKey, numbered))
                ? Optional.of(numbered)
                : Optional.empty();
    }

    private CitationRef addWithKey(String key, CitationRef citation) {
        CitationRef numbered = citation.withNumber(citationsByKey.size() + 1);
        citationsByKey.put(key, numbered);
        return numbered;
    }

    private String dedupeKey(CitationRef citation) {
        String url = normalizeUrl(citation.url());
        if (StringUtils.isNotBlank(url)) {
            return "%s|%s".formatted(citation.kind(), url);
        }

        return String.join(
                "|",
                citation.kind().name(),
                value(citation.title()),
                value(citation.documentIndex()),
                value(citation.documentTitle()),
                value(citation.fileId()),
                value(citation.startPage()),
                value(citation.endPage()),
                value(citation.startChar()),
                value(citation.endChar()),
                value(citation.startBlock()),
                value(citation.endBlock()),
                value(citation.source()),
                value(citation.searchResultIndex()),
                value(citation.citedText())
        );
    }

    private String occurrenceKey(String sourceKey, CitationRef citation) {
        if (citation.responseStartIndex() == null || citation.responseEndIndex() == null) {
            return sourceKey;
        }
        return "%s|%d|%d".formatted(
                sourceKey,
                citation.responseStartIndex(),
                citation.responseEndIndex()
        );
    }

    private String normalizeUrl(String url) {
        String normalized = StringUtils.trimToEmpty(url).replaceAll("[.,;:]+$", "");
        while (normalized.endsWith("/") || normalized.endsWith("#")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String value(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
