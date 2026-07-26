package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.provider.api.Message;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Application-owned stable identity and order for one conversation message. */
public record ConversationHistoryEntry(UUID messageId, int ordinal, Message message) {

    public ConversationHistoryEntry {
        Objects.requireNonNull(messageId, "messageId can't be null");
        Objects.requireNonNull(message, "message can't be null");
        if (ordinal <= 0) {
            throw new IllegalArgumentException("ordinal must be positive");
        }
        Instant normalizedTimestamp = Instant.ofEpochMilli(message.timestamp().toEpochMilli());
        message = new Message(message.role(), message.parts(), normalizedTimestamp, message.meta());
    }
}
