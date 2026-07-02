package com.github.drafael.chat4j.provider.api.content;

import lombok.Builder;
import org.apache.commons.lang3.StringUtils;

@Builder(toBuilder = true)
public record CitationRef(
        int number,
        CitationKind kind,
        String title,
        String citedText,
        String url,
        String encryptedIndex,
        Long documentIndex,
        String documentTitle,
        String fileId,
        Long startPage,
        Long endPage,
        Long startChar,
        Long endChar,
        Long startBlock,
        Long endBlock,
        String source,
        Long searchResultIndex
) {

    public CitationRef {
        kind = kind == null ? CitationKind.WEB : kind;
        title = StringUtils.defaultString(title);
        citedText = StringUtils.defaultString(citedText);
        url = StringUtils.defaultString(url);
        encryptedIndex = StringUtils.defaultString(encryptedIndex);
        documentTitle = StringUtils.defaultString(documentTitle);
        fileId = StringUtils.defaultString(fileId);
        source = StringUtils.defaultString(source);
    }

    public CitationRef withNumber(int newNumber) {
        return toBuilder()
                .number(newNumber)
                .build();
    }

    public String displayTitle() {
        if (StringUtils.isNotBlank(title)) {
            return title;
        }
        if (StringUtils.isNotBlank(documentTitle)) {
            return documentTitle;
        }
        if (StringUtils.isNotBlank(source)) {
            return source;
        }
        if (StringUtils.isNotBlank(fileId)) {
            return fileId;
        }
        if (documentIndex != null) {
            return "Document %d".formatted(documentIndex + 1);
        }
        return kind == CitationKind.WEB
                ? StringUtils.defaultIfBlank(url, "Citation %d".formatted(number))
                : "Citation %d".formatted(number);
    }

    public String locationLabel() {
        return switch (kind) {
            case WEB -> url;
            case DOCUMENT_PAGE -> rangeLabel("page", "pages", startPage, endPage);
            case DOCUMENT_CHAR -> rangeLabel("char", "chars", startChar, endChar);
            case DOCUMENT_BLOCK -> rangeLabel("block", "blocks", startBlock, endBlock);
            case SEARCH_RESULT -> searchResultLocationLabel();
        };
    }

    private String searchResultLocationLabel() {
        String searchResult = searchResultIndex == null ? "search result" : "search result %d".formatted(searchResultIndex + 1);
        String range = rangeLabel("block", "blocks", startBlock, endBlock);
        return StringUtils.isBlank(range) ? searchResult : "%s, %s".formatted(searchResult, range);
    }

    private static String rangeLabel(String singular, String plural, Long start, Long end) {
        if (start == null && end == null) {
            return "";
        }
        if (start == null || end == null || start.equals(end)) {
            Long value = start == null ? end : start;
            return "%s %d".formatted(singular, value);
        }
        return "%s %d-%d".formatted(plural, start, end);
    }
}
