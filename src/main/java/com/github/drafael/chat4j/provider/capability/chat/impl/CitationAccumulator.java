package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.content.CitationRef;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class CitationAccumulator {

    private final Map<String, CitationRef> citationsByKey = new LinkedHashMap<>();

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

        String key = dedupeKey(citation);
        return citationsByKey.containsKey(key) ? Optional.empty() : Optional.of(addWithKey(key, citation));
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
