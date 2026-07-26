package com.github.drafael.chat4j.persistence.conversation;

/** Indicates that a write may have committed and must be reconciled before explicit retry. */
public final class ConversationPersistenceIndeterminateException extends RuntimeException {

    public ConversationPersistenceIndeterminateException(Throwable cause) {
        super("Conversation persistence outcome is indeterminate", cause);
    }
}
