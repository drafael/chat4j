package com.github.drafael.chat4j.chat.export.pdf;

import com.github.drafael.chat4j.persistence.conversation.ConversationRepository.ConversationRecord;
import com.github.drafael.chat4j.persistence.conversation.ConversationRepository.LoadedConversation;
import com.github.drafael.chat4j.persistence.conversation.ConversationRepository.MessageRecord;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.MessageMeta;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationPdfDocumentTest {

    @Test
    @DisplayName("Export documents contain only durable user and assistant turns in ordinal order")
    void from_whenConversationContainsSystemMessages_keepsOnlyConversationTurns() {
        UUID conversationId = UUID.randomUUID();
        var conversation = new ConversationRecord(
                conversationId,
                "Conversation",
                "Anthropic",
                "model",
                false,
                "off",
                false,
                null,
                false,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        CitationRef citation = CitationRef.builder()
                .number(1)
                .kind(CitationKind.WEB)
                .title("Example")
                .url("https://example.com")
                .build();
        var loaded = new LoadedConversation(conversation, List.of(
                new MessageRecord(UUID.randomUUID(), 1, Message.system("internal instructions")),
                new MessageRecord(UUID.randomUUID(), 2, Message.user("question")),
                new MessageRecord(UUID.randomUUID(), 3, new Message(
                        Role.ASSISTANT,
                        List.of(new TextPart("answer\n\nSources:\n[1] [Example](<https://example.com>)")),
                        Instant.EPOCH,
                        new MessageMeta(
                                List.of("hidden skill"),
                                List.of("Used attachment fallback"),
                                true,
                                "visible error",
                                "private thinking",
                                "**Sources consulted**\n- [Example](<https://example.com>)",
                                List.of(),
                                List.of(citation)
                        )
                ))
        ));

        ConversationPdfDocument result = ConversationPdfDocument.from(loaded, Instant.EPOCH);

        assertThat(result.turns()).extracting(turn -> turn.role().name()).containsExactly("USER", "ASSISTANT");
        assertThat(result.turns().getLast())
                .extracting(
                        ConversationPdfDocument.Turn::fallbackNotices,
                        ConversationPdfDocument.Turn::cancelled,
                        ConversationPdfDocument.Turn::error,
                        ConversationPdfDocument.Turn::assistantWebSearch,
                        ConversationPdfDocument.Turn::textForRendering
                )
                .containsExactly(
                        List.of("Used attachment fallback"),
                        true,
                        "visible error",
                        "**Sources consulted**\n- [Example](<https://example.com>)",
                        "answer"
                );
    }
}
