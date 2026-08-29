package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantSourceFormatterTest {

    @Test
    @DisplayName("Native search activity adds one Sources section when citations arrive")
    void mergeWebSearchActivityWithAnswerSources_whenActivityHasSearchQuery_addsOneSourcesHeading() {
        List<CitationRef> citations = List.of(webCitation(
                1,
                "OpenAI docs",
                "https://platform.openai.com/docs"
        ));

        String merged = AssistantSourceFormatter.mergeWebSearchActivityWithAnswerSources(
                true,
                "Answer [1]",
                "**Searched**\n- current OpenAI docs",
                citations
        );

        assertThat(merged).contains("**Searched**", "**Sources**", "https://platform.openai.com/docs");
        assertThat(StringUtils.countMatches(merged, "**Sources**")).isEqualTo(1);
        assertThat(merged).doesNotContain("Source URLs will appear here");
    }

    @Test
    @DisplayName("Disabled Web Search leaves existing activity unchanged")
    void mergeWebSearchActivityWithAnswerSources_whenWebSearchIsDisabled_keepsExistingActivity() {
        String existing = "**Searched**\n- current OpenAI docs";

        String merged = AssistantSourceFormatter.mergeWebSearchActivityWithAnswerSources(
                false,
                "Answer with https://platform.openai.com/docs",
                existing,
                List.of(webCitation(1, "OpenAI docs", "https://platform.openai.com/docs"))
        );

        assertThat(merged).isEqualTo(existing);
    }

    @Test
    @DisplayName("Answer URLs supply Web Search activity when structured citations are absent")
    void mergeWebSearchActivityWithAnswerSources_whenCitationsAreAbsent_extractsAnswerUrls() {
        String merged = AssistantSourceFormatter.mergeWebSearchActivityWithAnswerSources(
                true,
                "Documentation: https://example.com/docs",
                "**Searched**\n- example documentation",
                List.of()
        );

        assertThat(merged)
                .contains("**Sources**")
                .contains("- <https://example.com/docs>");
    }

    @Test
    @DisplayName("Markdown source labels escape square brackets")
    void escapeMarkdownLinkLabel_whenLabelContainsBrackets_escapesBrackets() {
        assertThat(AssistantSourceFormatter.escapeMarkdownLinkLabel("Docs [current]"))
                .isEqualTo("Docs \\[current\\]");
    }

    @Test
    @DisplayName("Structured Web citations append a Sources section when the answer has no source references")
    void appendCitationSourcesIfNeeded_whenAnswerLacksSourceReferences_appendsSources() {
        List<CitationRef> citations = List.of(webCitation(
                1,
                "JNA GitHub",
                "https://github.com/java-native-access/jna"
        ));

        String text = AssistantSourceFormatter.appendCitationSourcesIfNeeded(
                "JNA docs are at https://github.com/java-native-access/jna.",
                citations
        );

        assertThat(text).contains("Sources:\n[1] [JNA GitHub](<https://github.com/java-native-access/jna>)");
    }

    @Test
    @DisplayName("Structured Web citation sources use the URL domain when the title is missing")
    void appendCitationSourcesIfNeeded_whenCitationTitleIsMissing_usesDomainLabel() {
        List<CitationRef> citations = List.of(webCitation(
                1,
                null,
                "https://www.example.com/articles/jna"
        ));

        String text = AssistantSourceFormatter.appendCitationSourcesIfNeeded("Answer", citations);

        assertThat(text).contains("Sources:\n[1] [example.com](<https://www.example.com/articles/jna>)");
    }

    @Test
    @DisplayName("Structured Web citation sources are ordered by citation number")
    void appendCitationSourcesIfNeeded_whenCitationsArriveOutOfOrder_ordersSourcesByNumber() {
        List<CitationRef> citations = List.of(
                webCitation(2, "Second", "https://example.com/two"),
                webCitation(1, "First", "https://example.com/one")
        );

        String text = AssistantSourceFormatter.appendCitationSourcesIfNeeded("Answer", citations);

        assertThat(text).containsSubsequence(
                "[1] [First](<https://example.com/one>)",
                "[2] [Second](<https://example.com/two>)"
        );
    }

    @Test
    @DisplayName("Structured Web citations do not duplicate labeled Markdown source references")
    void appendCitationSourcesIfNeeded_whenAnswerAlreadyHasLabeledMarkdownSourceReference_keepsAnswer() {
        List<CitationRef> citations = List.of(webCitation(
                1,
                "JNA GitHub",
                "https://github.com/java-native-access/jna"
        ));
        String existing = "Answer [1]\n\n[1] [JNA GitHub](<https://github.com/java-native-access/jna>)";

        String text = AssistantSourceFormatter.appendCitationSourcesIfNeeded(existing, citations);

        assertThat(text).isEqualTo(existing);
    }

    @Test
    @DisplayName("Structured Web citations with unsafe URLs are not appended")
    void appendCitationSourcesIfNeeded_whenWebCitationUrlIsNotHttp_doesNotAppendSource() {
        List<CitationRef> citations = List.of(webCitation(1, "Unsafe", "javascript:alert(1)"));

        String text = AssistantSourceFormatter.appendCitationSourcesIfNeeded("Answer", citations);

        assertThat(text).isEqualTo("Answer");
    }

    @Test
    @DisplayName("Structured non-Web citations are not appended as Web source URLs")
    void appendCitationSourcesIfNeeded_whenCitationIsNotWeb_doesNotAppendSource() {
        List<CitationRef> citations = List.of(CitationRef.builder()
                .number(1)
                .kind(CitationKind.DOCUMENT_PAGE)
                .title("doc.pdf")
                .startPage(1L)
                .build());

        String text = AssistantSourceFormatter.appendCitationSourcesIfNeeded("Answer", citations);

        assertThat(text).isEqualTo("Answer");
    }

    @Test
    @DisplayName("Structured Web citations do not duplicate existing source references")
    void appendCitationSourcesIfNeeded_whenAnswerAlreadyHasSourceReferences_keepsAnswer() {
        List<CitationRef> citations = List.of(webCitation(
                1,
                "JNA GitHub",
                "https://github.com/java-native-access/jna"
        ));
        String existing = "Answer [1]\n\nSources:\n[1] https://github.com/java-native-access/jna";

        String text = AssistantSourceFormatter.appendCitationSourcesIfNeeded(existing, citations);

        assertThat(text).isEqualTo(existing);
    }

    @Test
    @DisplayName("Sources are appended when an empty Sources heading ends before another section")
    void appendCitationSourcesIfNeeded_whenEmptySourcesHeadingEndsBeforeUrl_appendsSources() {
        List<CitationRef> citations = List.of(webCitation(
                1,
                "JNA GitHub",
                "https://github.com/java-native-access/jna"
        ));
        String answer = "Answer\n\nSources:\n\n## More reading\nhttps://example.com/unrelated";

        String text = AssistantSourceFormatter.appendCitationSourcesIfNeeded(answer, citations);

        assertThat(text).endsWith("Sources:\n[1] [JNA GitHub](<https://github.com/java-native-access/jna>)");
    }

    @Test
    @DisplayName("Structured Web citations do not duplicate an existing provider Sources section")
    void appendCitationSourcesIfNeeded_whenAnswerAlreadyHasSourcesSection_keepsAnswer() {
        List<CitationRef> citations = List.of(webCitation(
                1,
                "JNA GitHub",
                "https://github.com/java-native-access/jna"
        ));
        String existing = "Answer [1]\n\nSources:\n1. [JNA GitHub](<https://github.com/java-native-access/jna>)";

        String text = AssistantSourceFormatter.appendCitationSourcesIfNeeded(existing, citations);

        assertThat(text).isEqualTo(existing);
    }

    @Test
    @DisplayName("Structured Web citations do not duplicate bold Sources headings with trailing colons")
    void appendCitationSourcesIfNeeded_whenAnswerHasBoldSourcesHeadingWithColon_keepsAnswer() {
        List<CitationRef> citations = List.of(webCitation(
                1,
                "JNA GitHub",
                "https://github.com/java-native-access/jna"
        ));
        String existing = "Answer [1]\n\n**Sources**:\n- <https://github.com/java-native-access/jna>";

        String text = AssistantSourceFormatter.appendCitationSourcesIfNeeded(existing, citations);

        assertThat(text).isEqualTo(existing);
    }

    @Test
    @DisplayName("Sources are appended when a bold heading ends an empty Sources section")
    void appendCitationSourcesIfNeeded_whenEmptySourcesHeadingEndsBeforeBoldHeadingWithColon_appendsSources() {
        List<CitationRef> citations = List.of(webCitation(
                1,
                "JNA GitHub",
                "https://github.com/java-native-access/jna"
        ));
        String answer = "Answer\n\n**Sources**:\n\n**More reading**:\nhttps://example.com/unrelated";

        String text = AssistantSourceFormatter.appendCitationSourcesIfNeeded(answer, citations);

        assertThat(text).endsWith("Sources:\n[1] [JNA GitHub](<https://github.com/java-native-access/jna>)");
    }

    @Test
    @DisplayName("Structured Web citations do not duplicate existing Markdown source links")
    void appendCitationSourcesIfNeeded_whenAnswerAlreadyHasMarkdownSourceLinks_keepsAnswer() {
        List<CitationRef> citations = List.of(webCitation(
                1,
                "JNA GitHub",
                "https://github.com/java-native-access/jna"
        ));
        String existing = "Answer [1]\n\nSources:\n[1](https://github.com/java-native-access/jna)";

        String text = AssistantSourceFormatter.appendCitationSourcesIfNeeded(existing, citations);

        assertThat(text).isEqualTo(existing);
    }

    private CitationRef webCitation(int number, String title, String url) {
        return CitationRef.builder()
                .number(number)
                .kind(CitationKind.WEB)
                .title(title)
                .url(url)
                .build();
    }
}
