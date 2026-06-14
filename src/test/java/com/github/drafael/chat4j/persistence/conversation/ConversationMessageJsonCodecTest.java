package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMessageJsonCodecTest {

    private final ConversationMessageJsonCodec subject = new ConversationMessageJsonCodec();

    @Test
    @DisplayName("Generated image parts round-trip through content JSON")
    void deserializeMessage_whenGeneratedImagePartSerialized_returnsGeneratedImagePart() {
        UUID attachmentId = UUID.randomUUID();
        var attachment = new AttachmentRef(
                attachmentId,
                "/tmp/generated.png",
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
        Message restored = subject.deserializeMessage(Role.ASSISTANT.name(), message.content(), json, "{}", LocalDateTime.now());

        assertThat(restored.parts()).hasSize(2);
        assertThat(restored.parts().get(0)).isEqualTo(new TextPart("Here it is:"));
        assertThat(restored.parts().get(1)).isEqualTo(new GeneratedImagePart(attachment, 640, 480, "A cat"));
    }
}
