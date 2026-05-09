package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.provider.api.Message;
import lombok.NonNull;

import java.util.Objects;
import java.util.UUID;

public class AssistantMessageCompletionCoordinator {

    private final AssistantMessagePersister assistantMessagePersister;

    public AssistantMessageCompletionCoordinator(ConversationPersistenceCoordinator conversationPersistenceCoordinator) {
        this(conversationPersistenceCoordinator::persistAssistantMessage);
    }

    AssistantMessageCompletionCoordinator(@NonNull AssistantMessagePersister assistantMessagePersister) {
        this.assistantMessagePersister = assistantMessagePersister;
    }

    public PersistResult persist(UUID conversationId, Message message, UUID currentConversationId) throws Exception {
        if (conversationId == null || message == null) {
            return PersistResult.ignoredResult();
        }

        assistantMessagePersister.persist(conversationId, message);
        UUID conversationIdToSelect = Objects.equals(currentConversationId, conversationId)
                ? currentConversationId
                : null;

        return PersistResult.handledResult(conversationIdToSelect);
    }

    public record PersistResult(boolean handled, UUID conversationIdToSelect) {

        static PersistResult ignoredResult() {
            return new PersistResult(false, null);
        }

        static PersistResult handledResult(UUID conversationIdToSelect) {
            return new PersistResult(true, conversationIdToSelect);
        }
    }

    @FunctionalInterface
    interface AssistantMessagePersister {
        void persist(UUID conversationId, Message message) throws Exception;
    }
}
