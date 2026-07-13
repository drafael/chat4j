package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.openai.core.JsonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleCitationMapperTest {

    @Test
    @DisplayName("Responses URL annotations map to web citations")
    void fromResponseAnnotation_whenUrlCitationAnnotation_mapsCitation() {
        List<CitationRef> citations = OpenAiCompatibleCitationMapper.fromResponseAnnotation(JsonValue.from(Map.of(
                "type", "url_citation",
                "title", "OpenAI Docs",
                "url", "https://platform.openai.com/docs"
        )));

        assertThat(citations)
                .singleElement()
                .satisfies(citation -> {
                    assertThat(citation.kind()).isEqualTo(CitationKind.WEB);
                    assertThat(citation.title()).isEqualTo("OpenAI Docs");
                    assertThat(citation.url()).isEqualTo("https://platform.openai.com/docs");
                });
    }

    @Test
    @DisplayName("OpenRouter-style nested annotation properties map to web citations")
    void fromAdditionalProperties_whenAnnotationsContainNestedUrlCitations_mapsCitations() {
        List<CitationRef> citations = OpenAiCompatibleCitationMapper.fromAdditionalProperties(Map.of(
                "annotations", JsonValue.from(List.of(Map.of(
                        "url_citation", Map.of(
                                "title", "OpenRouter Docs",
                                "url", "https://openrouter.ai/docs"
                        )
                )))
        ));

        assertThat(citations)
                .singleElement()
                .satisfies(citation -> {
                    assertThat(citation.title()).isEqualTo("OpenRouter Docs");
                    assertThat(citation.url()).isEqualTo("https://openrouter.ai/docs");
                });
    }

    @Test
    @DisplayName("Groq executed tool search results map content as cited text")
    void fromAdditionalProperties_whenExecutedToolsContainSearchResults_mapsCitations() {
        List<CitationRef> citations = OpenAiCompatibleCitationMapper.fromAdditionalProperties(Map.of(
                "executed_tools", JsonValue.from(List.of(Map.of(
                        "type", "search",
                        "search_results", List.of(Map.of(
                                "title", "Groq Docs",
                                "url", "https://console.groq.com/docs/tool-use/built-in-tools/web-search",
                                "content", "Compound can search the web."
                        ))
                )))
        ));

        assertThat(citations)
                .singleElement()
                .satisfies(citation -> {
                    assertThat(citation.title()).isEqualTo("Groq Docs");
                    assertThat(citation.url()).isEqualTo("https://console.groq.com/docs/tool-use/built-in-tools/web-search");
                    assertThat(citation.citedText()).isEqualTo("Compound can search the web.");
                });
    }

    @Test
    @DisplayName("String URL citation arrays map to web citations")
    void fromAdditionalProperties_whenCitationsContainUrlStrings_mapsCitations() {
        List<CitationRef> citations = OpenAiCompatibleCitationMapper.fromAdditionalProperties(Map.of(
                "citations", JsonValue.from(List.of("https://docs.x.ai/docs/guides/tools"))
        ));

        assertThat(citations)
                .singleElement()
                .satisfies(citation -> assertThat(citation.url()).isEqualTo("https://docs.x.ai/docs/guides/tools"));
    }

    @Test
    @DisplayName("Citation string arrays ignore unsafe or malformed URL values")
    void fromAdditionalProperties_whenCitationsContainUnsafeOrMalformedUrls_ignoresValues() {
        List<CitationRef> citations = OpenAiCompatibleCitationMapper.fromAdditionalProperties(Map.of(
                "citations", JsonValue.from(List.of(
                        "not a url",
                        "javascript:alert(1)",
                        "mailto:source@example.com",
                        "https://",
                        "https://example.com/source\n- injected"
                ))
        ));

        assertThat(citations).isEmpty();
    }

    @Test
    @DisplayName("Plain content strings are not treated as citation URLs")
    void fromAdditionalProperties_whenContentIsPlainText_doesNotMapCitation() {
        List<CitationRef> citations = OpenAiCompatibleCitationMapper.fromAdditionalProperties(Map.of(
                "content", JsonValue.from("ordinary streamed text")
        ));

        assertThat(citations).isEmpty();
    }

    @Test
    @DisplayName("Blank URL annotations are ignored")
    void fromResponseAnnotation_whenUrlIsBlank_returnsEmptyList() {
        List<CitationRef> citations = OpenAiCompatibleCitationMapper.fromResponseAnnotation(JsonValue.from(Map.of(
                "type", "url_citation",
                "title", "Missing URL",
                "url", " "
        )));

        assertThat(citations).isEmpty();
    }
}
