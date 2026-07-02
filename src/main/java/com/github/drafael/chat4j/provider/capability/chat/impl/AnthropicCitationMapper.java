package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.anthropic.models.messages.CitationCharLocation;
import com.anthropic.models.messages.CitationContentBlockLocation;
import com.anthropic.models.messages.CitationPageLocation;
import com.anthropic.models.messages.CitationsDelta;
import com.anthropic.models.messages.CitationsSearchResultLocation;
import com.anthropic.models.messages.CitationsWebSearchResultLocation;
import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

final class AnthropicCitationMapper {

    private AnthropicCitationMapper() {
    }

    static Optional<CitationRef> fromDelta(CitationsDelta delta) {
        if (delta == null) {
            return Optional.empty();
        }

        try {
            return fromCitation(delta.citation());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static Optional<CitationRef> fromCitation(CitationsDelta.Citation citation) {
        if (citation == null) {
            return Optional.empty();
        }

        try {
            if (citation.isWebSearchResultLocation()) {
                return fromWebSearch(citation.asWebSearchResultLocation());
            }
            if (citation.isPageLocation()) {
                return Optional.of(fromPage(citation.asPageLocation()));
            }
            if (citation.isCharLocation()) {
                return Optional.of(fromChar(citation.asCharLocation()));
            }
            if (citation.isContentBlockLocation()) {
                return Optional.of(fromContentBlock(citation.asContentBlockLocation()));
            }
            if (citation.isSearchResultLocation()) {
                return Optional.of(fromSearchResult(citation.asSearchResultLocation()));
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<CitationRef> fromWebSearch(CitationsWebSearchResultLocation citation) {
        String title = citation.title().orElse("");
        String citedText = citation.citedText();
        if (StringUtils.isBlank(citation.url()) && StringUtils.isBlank(title) && StringUtils.isBlank(citedText)) {
            return Optional.empty();
        }
        return Optional.of(CitationRef.builder()
                .kind(CitationKind.WEB)
                .title(title)
                .citedText(citedText)
                .url(citation.url())
                .encryptedIndex(citation.encryptedIndex())
                .build());
    }

    private static CitationRef fromPage(CitationPageLocation citation) {
        return CitationRef.builder()
                .kind(CitationKind.DOCUMENT_PAGE)
                .title(citation.documentTitle().orElse(""))
                .citedText(citation.citedText())
                .documentIndex(citation.documentIndex())
                .documentTitle(citation.documentTitle().orElse(""))
                .fileId(citation.fileId().orElse(""))
                .startPage(citation.startPageNumber())
                .endPage(citation.endPageNumber())
                .build();
    }

    private static CitationRef fromChar(CitationCharLocation citation) {
        return CitationRef.builder()
                .kind(CitationKind.DOCUMENT_CHAR)
                .title(citation.documentTitle().orElse(""))
                .citedText(citation.citedText())
                .documentIndex(citation.documentIndex())
                .documentTitle(citation.documentTitle().orElse(""))
                .fileId(citation.fileId().orElse(""))
                .startChar(citation.startCharIndex())
                .endChar(citation.endCharIndex())
                .build();
    }

    private static CitationRef fromContentBlock(CitationContentBlockLocation citation) {
        return CitationRef.builder()
                .kind(CitationKind.DOCUMENT_BLOCK)
                .title(citation.documentTitle().orElse(""))
                .citedText(citation.citedText())
                .documentIndex(citation.documentIndex())
                .documentTitle(citation.documentTitle().orElse(""))
                .fileId(citation.fileId().orElse(""))
                .startBlock(citation.startBlockIndex())
                .endBlock(citation.endBlockIndex())
                .build();
    }

    private static CitationRef fromSearchResult(CitationsSearchResultLocation citation) {
        return CitationRef.builder()
                .kind(CitationKind.SEARCH_RESULT)
                .title(citation.title().orElse(""))
                .citedText(citation.citedText())
                .startBlock(citation.startBlockIndex())
                .endBlock(citation.endBlockIndex())
                .source(citation.source())
                .searchResultIndex(citation.searchResultIndex())
                .build();
    }
}
