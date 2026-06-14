package com.github.drafael.chat4j.persistence.conversation;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PersistedMessageCounter {

    private final ConcurrentHashMap<UUID, Integer> persistedCountByConversationId = new ConcurrentHashMap<>();

    public void markConversationCreated(UUID conversationId) {
        if (conversationId == null) {
            return;
        }

        persistedCountByConversationId.put(conversationId, 0);
    }

    public void markConversationLoaded(UUID conversationId, int persistedCount) {
        if (conversationId == null) {
            return;
        }

        persistedCountByConversationId.put(conversationId, Math.max(0, persistedCount));
    }

    public void markPersisted(UUID conversationId, int persistedCount) {
        if (conversationId == null) {
            return;
        }

        persistedCountByConversationId.put(conversationId, Math.max(0, persistedCount));
    }

    public void incrementIfPresent(UUID conversationId) {
        if (conversationId == null) {
            return;
        }

        persistedCountByConversationId.computeIfPresent(conversationId, (id, count) -> count + 1);
    }

    public int resolve(UUID conversationId, MessageCountLoader loader) throws Exception {
        if (conversationId == null) {
            return 0;
        }

        Integer cachedCount = persistedCountByConversationId.get(conversationId);
        if (cachedCount != null) {
            return cachedCount;
        }

        int loadedCount = Math.max(0, loader.load(conversationId));
        persistedCountByConversationId.put(conversationId, loadedCount);
        return loadedCount;
    }

    @FunctionalInterface
    public interface MessageCountLoader {
        int load(UUID conversationId) throws Exception;
    }
}
