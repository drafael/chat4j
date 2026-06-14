package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.provider.api.Message;
import java.util.Objects;
import java.util.UUID;
import lombok.NonNull;

public class AssistantMessageCompletionCoordinator {

    private final AssistantMessagePersister assistantMessagePersister;
    private final ConversationExistsChecker conversationExistsChecker;

    public AssistantMessageCompletionCoordinator(ConversationPersistenceCoordinator conversationPersistenceCoordinator) {
        this(
                conversationPersistenceCoordinator::persistAssistantMessage,
                conversationPersistenceCoordinator::conversationExists
        );
    }

    AssistantMessageCompletionCoordinator(@NonNull AssistantMessagePersister assistantMessagePersister) {
        this(assistantMessagePersister, conversationId -> true);
    }

    AssistantMessageCompletionCoordinator(
            @NonNull AssistantMessagePersister assistantMessagePersister,
            @NonNull ConversationExistsChecker conversationExistsChecker
    ) {
        this.assistantMessagePersister = assistantMessagePersister;
        this.conversationExistsChecker = conversationExistsChecker;
    }

    public PersistResult persist(UUID conversationId, Message message, UUID currentConversationId) throws Exception {
        if (conversationId == null || message == null) {
            return PersistResult.ignoredResult();
        }
        if (!conversationExistsChecker.exists(conversationId)) {
            return PersistResult.ignoredResult();
        }

        try {
            assistantMessagePersister.persist(conversationId, message);
        } catch (Exception e) {
            if (!conversationExistsChecker.exists(conversationId)) {
                return PersistResult.ignoredResult();
            }
            throw e;
        }
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

    @FunctionalInterface
    interface ConversationExistsChecker {
        boolean exists(UUID conversationId) throws Exception;
    }
}
