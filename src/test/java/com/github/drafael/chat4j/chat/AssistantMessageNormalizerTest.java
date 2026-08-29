package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AgentToolActivityMeta;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.MessageMeta;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.github.drafael.chat4j.chat.AssistantMessageNormalizer.normalizeLoadedHistory;
import static com.github.drafael.chat4j.chat.AssistantMessageNormalizer.normalizeThinkingText;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class AssistantMessageNormalizerTest {

    @Test
    @DisplayName("Null and empty histories normalize to empty results")
    void normalizeLoadedHistory_whenHistoryIsNullOrEmpty_returnsEmptyList() {
        assertThat(normalizeLoadedHistory(null)).isEmpty();
        assertThat(normalizeLoadedHistory(emptyList())).isEmpty();
    }

    @Test
    @DisplayName("Non-assistant messages and singleton assistant runs retain identity and order")
    void normalizeLoadedHistory_whenRunsContainSingletonAssistant_preservesMessageIdentityAndInput() {
        Message firstSystem = message(Role.SYSTEM, "system", Instant.parse("2026-01-01T00:00:00Z"));
        Message singletonAssistant = message(Role.ASSISTANT, "single", Instant.parse("2026-01-01T00:00:01Z"));
        Message user = message(Role.USER, "question", Instant.parse("2026-01-01T00:00:02Z"));
        Message lastSystem = message(Role.SYSTEM, "system two", Instant.parse("2026-01-01T00:00:03Z"));
        List<Message> messages = new ArrayList<>(List.of(firstSystem, singletonAssistant, user, lastSystem));
        List<Message> originalMessages = List.copyOf(messages);

        List<Message> normalized = normalizeLoadedHistory(messages);

        assertThat(normalized).hasSize(4);
        assertThat(normalized.get(0)).isSameAs(firstSystem);
        assertThat(normalized.get(1)).isSameAs(singletonAssistant);
        assertThat(normalized.get(2)).isSameAs(user);
        assertThat(normalized.get(3)).isSameAs(lastSystem);
        assertThat(messages).containsExactlyElementsOf(originalMessages);
        assertThat(messages.get(0)).isSameAs(firstSystem);
        assertThat(messages.get(1)).isSameAs(singletonAssistant);
        assertThat(messages.get(2)).isSameAs(user);
        assertThat(messages.get(3)).isSameAs(lastSystem);
    }

    @Test
    @DisplayName("Each maximal assistant run becomes one message without crossing other roles")
    void normalizeLoadedHistory_whenHistoryContainsMultipleAssistantRuns_mergesEachMaximalRun() {
        Message firstSystem = message(Role.SYSTEM, "system", Instant.parse("2026-01-01T00:00:00Z"));
        Message firstAssistant = message(Role.ASSISTANT, "first", Instant.parse("2026-01-01T00:00:01Z"));
        Message secondAssistant = message(Role.ASSISTANT, "second", Instant.parse("2026-01-01T00:00:02Z"));
        Message user = message(Role.USER, "question", Instant.parse("2026-01-01T00:00:03Z"));
        Message thirdAssistant = message(Role.ASSISTANT, "third", Instant.parse("2026-01-01T00:00:04Z"));
        Message fourthAssistant = message(Role.ASSISTANT, "fourth", Instant.parse("2026-01-01T00:00:05Z"));

        List<Message> normalized = normalizeLoadedHistory(List.of(
                firstSystem,
                firstAssistant,
                secondAssistant,
                user,
                thirdAssistant,
                fourthAssistant
        ));

        assertThat(normalized).hasSize(4);
        assertThat(normalized.get(0)).isSameAs(firstSystem);
        assertThat(normalized.get(1).content()).isEqualTo("second");
        assertThat(normalized.get(2)).isSameAs(user);
        assertThat(normalized.get(3).content()).isEqualTo("fourth");
    }

    @Test
    @DisplayName("The last nonblank assistant supplies primary content while activities aggregate in order")
    void normalizeLoadedHistory_whenRunHasMultiplePayloads_preservesPrimaryAndAggregatesActivities() {
        AgentToolActivityMeta repeatedTool = new AgentToolActivityMeta(
                "read-1",
                "read",
                "SUCCEEDED",
                "path=one.txt",
                ""
        );
        AgentToolActivityMeta primaryTool = new AgentToolActivityMeta(
                "grep-1",
                "grep",
                "FAILED",
                "query=needle",
                "not found"
        );
        CitationRef citation = CitationRef.builder()
                .number(1)
                .kind(CitationKind.WEB)
                .title("Primary source")
                .url("https://primary.example/source")
                .build();
        Message first = new Message(
                Role.ASSISTANT,
                List.of(new TextPart("earlier answer")),
                Instant.parse("2026-02-01T00:00:00Z"),
                new MessageMeta(
                        List.of("earlier-skill"),
                        List.of("earlier fallback"),
                        false,
                        "earlier error",
                        "first thinking",
                        "**Searched**\n- first query",
                        List.of(repeatedTool),
                        emptyList()
                )
        );
        List<ContentPart> primaryParts = List.of(
                new TextPart("primary answer"),
                new FilePart(new AttachmentRef(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        "/stored/answer.txt",
                        "answer.txt",
                        "text/plain",
                        42,
                        "sha256"
                ))
        );
        Instant primaryTimestamp = Instant.parse("2026-02-01T00:00:01Z");
        Message primary = new Message(
                Role.ASSISTANT,
                primaryParts,
                primaryTimestamp,
                new MessageMeta(
                        List.of("primary-skill"),
                        List.of("primary fallback"),
                        true,
                        "primary error",
                        "\u001B[32mprimary thinking\u001B[0m",
                        "**Sources**\n- https://primary.example/source",
                        List.of(primaryTool),
                        List.of(citation)
                )
        );
        Message trailingArtifact = new Message(
                Role.ASSISTANT,
                List.of(new TextPart(" \n")),
                Instant.parse("2026-02-01T00:00:02Z"),
                new MessageMeta(
                        List.of("artifact-skill"),
                        List.of("artifact fallback"),
                        false,
                        "artifact error",
                        "artifact\u200B thinking",
                        "**Searched**\n- second query\n\n**Sources consulted**\n- https://second.example/source",
                        List.of(repeatedTool),
                        emptyList()
                )
        );

        List<Message> messages = new ArrayList<>(List.of(first, primary, trailingArtifact));
        List<Message> originalMessages = List.copyOf(messages);

        List<Message> normalized = normalizeLoadedHistory(messages);

        assertThat(normalized).hasSize(1);
        Message merged = normalized.getFirst();
        assertThat(merged.role()).isEqualTo(Role.ASSISTANT);
        assertThat(merged.parts()).containsExactlyElementsOf(primaryParts);
        assertThat(merged.timestamp()).isEqualTo(primaryTimestamp);
        assertThat(merged.meta().activeSkills()).containsExactly("primary-skill");
        assertThat(merged.meta().fallbackNotices()).containsExactly("primary fallback");
        assertThat(merged.meta().cancelled()).isTrue();
        assertThat(merged.meta().error()).isEqualTo("primary error");
        assertThat(merged.meta().citations()).containsExactly(citation);
        assertThat(merged.meta().assistantThinking())
                .isEqualTo("first thinking\n\nprimary thinking\n\nartifact thinking");
        assertThat(merged.meta().assistantWebSearch()).isEqualTo("""
                **Searched**
                - first query
                - second query

                **Sources**
                - https://primary.example/source

                **Sources consulted**
                - https://second.example/source
                """.trim());
        assertThat(merged.meta().agentToolActivities())
                .containsExactly(repeatedTool, primaryTool, repeatedTool);
        assertThat(first.meta().assistantThinking()).isEqualTo("first thinking");
        assertThat(primary.meta().assistantThinking()).contains("\u001B[32m");
        assertThat(trailingArtifact.meta().assistantThinking()).contains("\u200B");
        assertThat(messages).containsExactlyElementsOf(originalMessages);
        assertThat(messages).containsExactly(first, primary, trailingArtifact);
    }

    @Test
    @DisplayName("An all-blank assistant run uses its last message as the primary")
    void normalizeLoadedHistory_whenAllAssistantContentIsBlank_usesLastMessageAsPrimary() {
        Message first = new Message(
                Role.ASSISTANT,
                List.of(new TextPart(" ")),
                Instant.parse("2026-03-01T00:00:00Z"),
                new MessageMeta(List.of("first"), List.of("first fallback"), false, "first error")
        );
        List<ContentPart> lastParts = List.of(new TextPart("\n\t"));
        Instant lastTimestamp = Instant.parse("2026-03-01T00:00:01Z");
        Message last = new Message(
                Role.ASSISTANT,
                lastParts,
                lastTimestamp,
                new MessageMeta(List.of("last"), List.of("last fallback"), true, "last error")
        );

        List<Message> messages = new ArrayList<>(List.of(first, last));
        List<Message> originalMessages = List.copyOf(messages);

        List<Message> normalized = normalizeLoadedHistory(messages);

        assertThat(normalized).hasSize(1);
        Message merged = normalized.getFirst();
        assertThat(merged.parts()).containsExactlyElementsOf(lastParts);
        assertThat(merged.timestamp()).isEqualTo(lastTimestamp);
        assertThat(merged.meta().activeSkills()).containsExactly("last");
        assertThat(merged.meta().fallbackNotices()).containsExactly("last fallback");
        assertThat(merged.meta().cancelled()).isTrue();
        assertThat(merged.meta().error()).isEqualTo("last error");
        assertThat(messages).containsExactlyElementsOf(originalMessages);
        assertThat(messages).containsExactly(first, last);
    }

    @Test
    @DisplayName("Thinking cleanup removes terminal controls and invisible formatting while retaining layout")
    void normalizeThinkingText_whenTextContainsFormattingArtifacts_returnsVisibleNormalizedText() {
        String text = "\u001B[31mPlan\u001B[0m\r\nA\u00A0B\rC\u200B\u0000\tD\u007F";

        assertThat(normalizeThinkingText(text)).isEqualTo("Plan\nA B\nC\tD");
        assertThat(normalizeThinkingText("\u200B\u200C\u200D\uFEFF\u0000")).isEmpty();
        assertThat(normalizeThinkingText(null)).isEmpty();
    }

    private Message message(Role role, String content, Instant timestamp) {
        return new Message(role, List.of(new TextPart(content)), timestamp, MessageMeta.empty());
    }
}
