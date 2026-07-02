package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.anthropic.models.messages.CitationCharLocation;
import com.anthropic.models.messages.CitationContentBlockLocation;
import com.anthropic.models.messages.CitationPageLocation;
import com.anthropic.models.messages.CitationsDelta;
import com.anthropic.models.messages.CitationsSearchResultLocation;
import com.anthropic.models.messages.CitationsWebSearchResultLocation;
import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicCitationMapperTest {

    @Test
    @DisplayName("Web search citations map to URL-backed citation refs")
    void fromCitation_whenWebSearchLocation_returnsWebCitation() {
        var anthropicCitation = CitationsWebSearchResultLocation.builder()
                .url("https://example.com/a")
                .title("Example")
                .citedText("quoted web text")
                .encryptedIndex("enc")
                .build();

        CitationRef citation = AnthropicCitationMapper.fromCitation(CitationsDelta.Citation.ofWebSearchResultLocation(anthropicCitation)).orElseThrow();

        assertThat(citation.kind()).isEqualTo(CitationKind.WEB);
        assertThat(citation.url()).isEqualTo("https://example.com/a");
        assertThat(citation.title()).isEqualTo("Example");
        assertThat(citation.citedText()).isEqualTo("quoted web text");
        assertThat(citation.encryptedIndex()).isEqualTo("enc");
    }

    @Test
    @DisplayName("Document page citations map page and document metadata")
    void fromCitation_whenPageLocation_returnsDocumentPageCitation() {
        var anthropicCitation = CitationPageLocation.builder()
                .documentIndex(2)
                .documentTitle("earth.pdf")
                .fileId("file_123")
                .startPageNumber(4)
                .endPageNumber(5)
                .citedText("quoted page text")
                .build();

        CitationRef citation = AnthropicCitationMapper.fromCitation(CitationsDelta.Citation.ofPageLocation(anthropicCitation)).orElseThrow();

        assertThat(citation.kind()).isEqualTo(CitationKind.DOCUMENT_PAGE);
        assertThat(citation.documentIndex()).isEqualTo(2);
        assertThat(citation.documentTitle()).isEqualTo("earth.pdf");
        assertThat(citation.fileId()).isEqualTo("file_123");
        assertThat(citation.startPage()).isEqualTo(4);
        assertThat(citation.endPage()).isEqualTo(5);
        assertThat(citation.citedText()).isEqualTo("quoted page text");
    }

    @Test
    @DisplayName("Document char citations map char range metadata")
    void fromCitation_whenCharLocation_returnsDocumentCharCitation() {
        var anthropicCitation = CitationCharLocation.builder()
                .documentIndex(0)
                .documentTitle("notes.txt")
                .fileId("file_char")
                .startCharIndex(10)
                .endCharIndex(42)
                .citedText("quoted char text")
                .build();

        CitationRef citation = AnthropicCitationMapper.fromCitation(CitationsDelta.Citation.ofCharLocation(anthropicCitation)).orElseThrow();

        assertThat(citation.kind()).isEqualTo(CitationKind.DOCUMENT_CHAR);
        assertThat(citation.startChar()).isEqualTo(10);
        assertThat(citation.endChar()).isEqualTo(42);
        assertThat(citation.displayTitle()).isEqualTo("notes.txt");
    }

    @Test
    @DisplayName("Document content block citations map block range metadata")
    void fromCitation_whenContentBlockLocation_returnsDocumentBlockCitation() {
        var anthropicCitation = CitationContentBlockLocation.builder()
                .documentIndex(1)
                .documentTitle("blocks.md")
                .fileId("file_456")
                .startBlockIndex(3)
                .endBlockIndex(7)
                .citedText("quoted block text")
                .build();

        CitationRef citation = AnthropicCitationMapper.fromCitation(CitationsDelta.Citation.ofContentBlockLocation(anthropicCitation)).orElseThrow();

        assertThat(citation.kind()).isEqualTo(CitationKind.DOCUMENT_BLOCK);
        assertThat(citation.fileId()).isEqualTo("file_456");
        assertThat(citation.startBlock()).isEqualTo(3);
        assertThat(citation.endBlock()).isEqualTo(7);
    }

    @Test
    @DisplayName("Search result citations map source and block range metadata")
    void fromCitation_whenSearchResultLocation_returnsSearchResultCitation() {
        var anthropicCitation = CitationsSearchResultLocation.builder()
                .source("internal-search")
                .title("Search Hit")
                .searchResultIndex(4)
                .startBlockIndex(1)
                .endBlockIndex(2)
                .citedText("quoted search text")
                .build();

        CitationRef citation = AnthropicCitationMapper.fromCitation(CitationsDelta.Citation.ofSearchResultLocation(anthropicCitation)).orElseThrow();

        assertThat(citation.kind()).isEqualTo(CitationKind.SEARCH_RESULT);
        assertThat(citation.source()).isEqualTo("internal-search");
        assertThat(citation.searchResultIndex()).isEqualTo(4);
        assertThat(citation.startBlock()).isEqualTo(1);
        assertThat(citation.endBlock()).isEqualTo(2);
    }

    @Test
    @DisplayName("Web search citations without URLs become non-navigable citation refs when useful text exists")
    void fromCitation_whenWebSearchLocationHasNoUrl_returnsNonNavigableCitation() {
        var anthropicCitation = CitationsWebSearchResultLocation.builder()
                .url("")
                .title("Broken")
                .citedText("quoted")
                .encryptedIndex("enc")
                .build();

        CitationRef citation = AnthropicCitationMapper.fromCitation(CitationsDelta.Citation.ofWebSearchResultLocation(anthropicCitation)).orElseThrow();

        assertThat(citation.kind()).isEqualTo(CitationKind.WEB);
        assertThat(citation.url()).isEmpty();
        assertThat(citation.displayTitle()).isEqualTo("Broken");
        assertThat(citation.citedText()).isEqualTo("quoted");
    }

    @Test
    @DisplayName("Citation accumulator reuses stable numbers for duplicates")
    void add_whenCitationRepeats_reusesExistingNumber() {
        var subject = new CitationAccumulator();
        var first = CitationRef.builder()
                .kind(CitationKind.WEB)
                .title("Example")
                .citedText("one")
                .url("https://example.com/")
                .build();
        var duplicate = CitationRef.builder()
                .kind(CitationKind.WEB)
                .title("Example")
                .citedText("two")
                .url("https://example.com")
                .build();
        var other = CitationRef.builder()
                .kind(CitationKind.DOCUMENT_PAGE)
                .title("Doc")
                .citedText("quote")
                .documentIndex(0L)
                .documentTitle("Doc")
                .startPage(1L)
                .endPage(1L)
                .build();

        CitationRef numberedFirst = subject.add(first);
        CitationRef numberedDuplicate = subject.add(duplicate);
        CitationRef numberedOther = subject.add(other);

        assertThat(numberedFirst.number()).isEqualTo(1);
        assertThat(numberedDuplicate.number()).isEqualTo(1);
        assertThat(numberedOther.number()).isEqualTo(2);
    }
}
