package com.github.drafael.chat4j.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FindingCardParserTest {

    @Test
    @DisplayName("Parser extracts priority findings from markdown-ish code review output")
    void parse_whenMarkdownFindingsProvided_extractsCards() {
        String markdown = """
                Findings

                P1 Agent bash escapes selected root
                Agent Mode documents bash as running within the selected folder, but handleBash only sets cwd.
                LocalToolRuntime.java:218-233 ↗

                - P2 History ordering can be nondeterministic
                Messages are loaded with ORDER BY created_at only.
                /Users/me/code/chat4j/storage/ConversationRepo.java:260-263
                """;

        var findings = FindingCardParser.parse(markdown);

        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).severity()).isEqualTo("P1");
        assertThat(findings.get(0).title()).isEqualTo("Agent bash escapes selected root");
        assertThat(findings.get(0).body()).contains("handleBash only sets cwd");
        assertThat(findings.get(0).fileReference()).isEqualTo("LocalToolRuntime.java:218-233");
        assertThat(findings.get(1).severity()).isEqualTo("P2");
        assertThat(findings.get(1).fileReference()).endsWith("ConversationRepo.java:260-263");
    }

    @Test
    @DisplayName("Parser accepts numbered finding lists")
    void parse_whenNumberedFindingsProvided_extractsCards() {
        var findings = FindingCardParser.parse("""
                Findings

                1. P1 Shell escape
                Bash can escape the selected root.
                LocalToolRuntime.java:218

                2) LOW Minor copy issue
                Button text is vague.
                ChatPanel.java:42
                """);

        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).severity()).isEqualTo("P1");
        assertThat(findings.get(0).title()).isEqualTo("Shell escape");
        assertThat(findings.get(1).severity()).isEqualTo("P3");
        assertThat(findings.get(1).title()).isEqualTo("Minor copy issue");
    }

    @Test
    @DisplayName("Parser normalizes textual severities")
    void parse_whenTextualSeveritiesProvided_normalizesToPriorityLabels() {
        var findings = FindingCardParser.parse("""
                Findings:

                [HIGH] Unsafe shell access
                Shell access can escape the selected folder.
                LocalToolRuntime.java:200

                MEDIUM - History ordering issue
                created_at ties can reorder messages.
                ConversationRepo.java:260
                """);

        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).severity()).isEqualTo("P1");
        assertThat(findings.get(0).title()).isEqualTo("Unsafe shell access");
        assertThat(findings.get(1).severity()).isEqualTo("P2");
        assertThat(findings.get(1).title()).isEqualTo("History ordering issue");
    }

    @Test
    @DisplayName("Parser ignores priority-looking prose without findings marker")
    void parse_whenNoFindingsMarker_ignoresPriorityLookingProse() {
        assertThat(FindingCardParser.parse("P1 is a common support priority label in many systems.")).isEmpty();
    }

    @Test
    @DisplayName("Parser returns empty list for normal assistant prose")
    void parse_whenNoFindingTitles_returnsEmptyList() {
        assertThat(FindingCardParser.parse("Here is a regular answer without priorities.")).isEmpty();
    }
}
