package com.github.drafael.chat4j.storage;

import org.apache.commons.lang3.Validate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class ConversationLoadCoordinator {

    private final ConversationRepo conversationRepo;
    private final AtomicLong requestCounter = new AtomicLong();

    public ConversationLoadCoordinator(ConversationRepo conversationRepo) {
        this.conversationRepo = Validate.notNull(conversationRepo, "conversationRepo must not be null");
    }

    public long loadAsync(UUID conversationId, Listener listener) {
        Validate.notNull(conversationId, "conversationId must not be null");
        Validate.notNull(listener, "listener must not be null");

        long requestId = requestCounter.incrementAndGet();
        Thread.startVirtualThread(() -> {
            try {
                List<ConversationRepo.MessageRecord> records = conversationRepo.getMessages(conversationId);
                Optional<ConversationRepo.ConversationRecord> conversation = conversationRepo.findById(conversationId);
                listener.onLoaded(requestId, conversationId, records, conversation);
            } catch (Exception e) {
                listener.onFailure(requestId, conversationId, e);
            }
        });

        return requestId;
    }

    public boolean isCurrentRequest(long requestId) {
        return requestId == requestCounter.get();
    }

    public interface Listener {
        void onLoaded(
                long requestId,
                UUID conversationId,
                List<ConversationRepo.MessageRecord> records,
                Optional<ConversationRepo.ConversationRecord> conversation
        );

        void onFailure(long requestId, UUID conversationId, Exception error);
    }
}
