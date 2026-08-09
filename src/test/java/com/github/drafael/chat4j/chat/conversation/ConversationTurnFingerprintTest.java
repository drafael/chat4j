package com.github.drafael.chat4j.chat.conversation;

import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationTurnFingerprintTest {

    @Test
    @DisplayName("Turn fingerprints are stable for equal durable content and change with message text")
    void create_whenDurableContentChanges_returnsDifferentFingerprint() {
        String first = ConversationTurnFingerprint.create(
                Role.ASSISTANT,
                List.of(new TextPart("first conversation")),
                List.of(),
                false,
                "",
                List.of()
        );
        String equal = ConversationTurnFingerprint.create(
                Role.ASSISTANT,
                List.of(new TextPart("first conversation")),
                List.of(),
                false,
                "",
                List.of()
        );
        String changed = ConversationTurnFingerprint.create(
                Role.ASSISTANT,
                List.of(new TextPart("second conversation")),
                List.of(),
                false,
                "",
                List.of()
        );

        assertThat(equal).isEqualTo(first);
        assertThat(changed).isNotEqualTo(first);
    }

    @Test
    @DisplayName("Turn fingerprints preserve boundaries between parts and fallback notices")
    void create_whenValuesCrossCollectionBoundaries_returnsDifferentFingerprint() {
        String partClassName = TextPart.class.getName();
        String first = ConversationTurnFingerprint.create(
                Role.ASSISTANT,
                List.of(new TextPart("alpha")),
                List.of(partClassName, "beta"),
                false,
                "",
                List.of()
        );
        String second = ConversationTurnFingerprint.create(
                Role.ASSISTANT,
                List.of(new TextPart("alpha"), new TextPart("beta")),
                List.of(),
                false,
                "",
                List.of()
        );

        assertThat(second).isNotEqualTo(first);
    }
}
