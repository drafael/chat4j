package com.github.drafael.chat4j.persistence.conversation;

/**
 * Signals that persistence could not reach the requested mutation because an earlier recovery outcome is unresolved.
 */
public class ConversationPersistencePrerequisiteIndeterminateException extends Exception {

    public ConversationPersistencePrerequisiteIndeterminateException(Throwable cause) {
        super("A prerequisite conversation persistence outcome is indeterminate", cause);
    }
}
