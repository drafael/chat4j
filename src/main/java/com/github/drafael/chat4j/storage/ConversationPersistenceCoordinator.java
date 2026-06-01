package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import lombok.NonNull;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public class ConversationPersistenceCoordinator {

    private final ConversationRepo conversationRepo;
    private final PersistedMessageCounter persistedMessageCounter;

    public ConversationPersistenceCoordinator(
            @NonNull ConversationRepo conversationRepo,
            @NonNull PersistedMessageCounter persistedMessageCounter
    ) {
        this.conversationRepo = conversationRepo;
        this.persistedMessageCounter = persistedMessageCounter;
    }

    public UUID createConversation(String title, String provider, String model) throws Exception {
        UUID conversationId = conversationRepo.createConversation(title, provider, model);
        persistedMessageCounter.markConversationCreated(conversationId);
        return conversationId;
    }

    public int persistConversationHistory(@NonNull UUID conversationId, @NonNull List<Message> history) throws Exception  {

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

    public void persistAssistantMessage(@NonNull UUID conversationId, @NonNull Message message) throws Exception  {

        conversationRepo.addMessage(conversationId, message);
        persistedMessageCounter.incrementIfPresent(conversationId);
    }

    public boolean persistMessageIfConversationExists(
            @NonNull UUID conversationId,
            @NonNull Message message
    ) throws Exception {
        if (!conversationExists(conversationId)) {
            return false;
        }

        try {
            persistAssistantMessage(conversationId, message);
            return true;
        } catch (Exception e) {
            if (!conversationExists(conversationId)) {
                return false;
            }
            throw e;
        }
    }

    public boolean conversationExists(@NonNull UUID conversationId) throws Exception {
        return conversationRepo.findById(conversationId).isPresent();
    }

    public void persistConversationAgentSettings(@NonNull UUID conversationId, boolean agentModeEnabled, Path agentProjectRoot)
            throws Exception  {
        conversationRepo.updateAgentSettings(conversationId, agentModeEnabled, agentProjectRoot);
    }

    public void persistConversationReasoningLevel(@NonNull UUID conversationId, ReasoningLevel reasoningLevel) throws Exception  {
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
