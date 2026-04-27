package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import org.apache.commons.lang3.Validate;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public class ConversationPersistenceCoordinator {

    private final ConversationRepo conversationRepo;
    private final PersistedMessageCounter persistedMessageCounter;

    public ConversationPersistenceCoordinator(
            ConversationRepo conversationRepo,
            PersistedMessageCounter persistedMessageCounter
    ) {
        this.conversationRepo = Validate.notNull(conversationRepo, "conversationRepo must not be null");
        this.persistedMessageCounter = Validate.notNull(
                persistedMessageCounter,
                "persistedMessageCounter must not be null"
        );
    }

    public UUID createConversation(String title, String provider, String model) throws Exception {
        UUID conversationId = conversationRepo.createConversation(title, provider, model);
        persistedMessageCounter.markConversationCreated(conversationId);
        return conversationId;
    }

    public int persistConversationHistory(UUID conversationId, List<Message> history) throws Exception {
        Validate.notNull(conversationId, "conversationId must not be null");
        Validate.notNull(history, "history must not be null");

        int persistedCount = resolvePersistedCount(conversationId);
        persistedCount = Math.min(persistedCount, history.size());

        for (int index = persistedCount; index < history.size(); index++) {
            Message message = history.get(index);
            conversationRepo.addMessage(conversationId, message);
            persistedCount++;
        }

        persistedMessageCounter.markPersisted(conversationId, persistedCount);
        return persistedCount;
    }

    public void persistAssistantMessage(UUID conversationId, Message message) throws Exception {
        Validate.notNull(conversationId, "conversationId must not be null");
        Validate.notNull(message, "message must not be null");

        conversationRepo.addMessage(conversationId, message);
        persistedMessageCounter.incrementIfPresent(conversationId);
    }

    public void persistConversationAgentSettings(UUID conversationId, boolean agentModeEnabled, Path agentProjectRoot)
            throws Exception {
        Validate.notNull(conversationId, "conversationId must not be null");
        conversationRepo.updateAgentSettings(conversationId, agentModeEnabled, agentProjectRoot);
    }

    public void persistConversationReasoningLevel(UUID conversationId, ReasoningLevel reasoningLevel) throws Exception {
        Validate.notNull(conversationId, "conversationId must not be null");
        conversationRepo.updateReasoningLevel(conversationId, reasoningLevel);
    }

    public void markConversationLoaded(UUID conversationId, int persistedCount) {
        persistedMessageCounter.markConversationLoaded(conversationId, persistedCount);
    }

    private int resolvePersistedCount(UUID conversationId) throws Exception {
        return persistedMessageCounter.resolve(
                conversationId,
                id -> conversationRepo.getMessages(id).size()
        );
    }
}
