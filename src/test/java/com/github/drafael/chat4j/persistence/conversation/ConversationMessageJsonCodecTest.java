package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.MessageMeta;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMessageJsonCodecTest {

    private final ConversationMessageJsonCodec subject = new ConversationMessageJsonCodec();

    @Test
    @DisplayName("Generated image parts round-trip through content JSON")
    void deserializeMessage_whenGeneratedImagePartSerialized_returnsGeneratedImagePart() {
        UUID attachmentId = UUID.randomUUID();
        String storagePath = Path.of("generated.png").toAbsolutePath().normalize().toString();
        var attachment = new AttachmentRef(
                attachmentId,
                storagePath,
                "generated.png",
                "image/png",
                123L,
                "abc"
        );
        var message = new Message(
                Role.ASSISTANT,
                List.of(new TextPart("Here it is:"), new GeneratedImagePart(attachment, 640, 480, "A cat")),
                Instant.now()
        );

        String json = subject.serializeParts(message.parts());
        Message restored = subject.deserializeMessage(Role.ASSISTANT.name(), message.content(), json, "{}", Instant.now());

        assertThat(restored.parts()).hasSize(2);
        assertThat(restored.parts().get(0)).isEqualTo(new TextPart("Here it is:"));
        assertThat(restored.parts().get(1)).isEqualTo(new GeneratedImagePart(attachment, 640, 480, "A cat"));
    }

    @Test
    @DisplayName("Citation metadata round-trips through meta JSON")
    void deserializeMessage_whenCitationMetaSerialized_restoresCitations() {
        var citation = CitationRef.builder()
                .number(1)
                .kind(CitationKind.DOCUMENT_PAGE)
                .title("earth.pdf")
                .citedText("quoted text")
                .documentIndex(0L)
                .documentTitle("earth.pdf")
                .fileId("file_123")
                .startPage(4L)
                .endPage(5L)
                .build();
        var meta = new MessageMeta(List.of("skill"), List.of("notice"), false, "", "thinking", "web", List.of(), List.of(citation));

        String metaJson = subject.serializeMeta(meta);
        Message restored = subject.deserializeMessage(Role.ASSISTANT.name(), "Answer [1]", "", metaJson, Instant.now());

        assertThat(restored.meta().citations()).containsExactly(citation);
    }

    @Test
    @DisplayName("Missing and malformed citations load as empty metadata")
    void deserializeMessage_whenCitationMetaMissingOrMalformed_ignoresInvalidEntries() {
        String metaJson = """
                {
                  "activeSkills": [],
                  "fallbackNotices": [],
                  "cancelled": false,
                  "error": "",
                  "assistantThinking": "",
                  "assistantWebSearch": "",
                  "agentToolActivities": [],
                  "citations": [
                    { "number": 0, "kind": "WEB", "url": "https://example.com" },
                    { "number": 1, "kind": "NOPE", "url": "https://example.com" },
                    "bad"
                  ]
                }
                """;

        Message restored = subject.deserializeMessage(Role.ASSISTANT.name(), "Answer", "", metaJson, Instant.now());

        assertThat(restored.meta().citations()).isEmpty();
    }
}
