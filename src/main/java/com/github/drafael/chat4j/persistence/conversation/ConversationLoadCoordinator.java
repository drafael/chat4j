package com.github.drafael.chat4j.persistence.conversation;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lombok.NonNull;

public class ConversationLoadCoordinator {

    private final ConversationRepository conversationRepo;
    private final AtomicLong requestCounter = new AtomicLong();

    public ConversationLoadCoordinator(@NonNull ConversationRepository conversationRepo) {
        this.conversationRepo = conversationRepo;
    }

    public long loadAsync(@NonNull UUID conversationId, @NonNull Listener listener) {

        long requestId = requestCounter.incrementAndGet();
        Thread.startVirtualThread(() -> {
            try {
                List<ConversationRepository.MessageRecord> records = conversationRepo.getMessages(conversationId);
                ConversationRepository.ConversationRecord conversation = conversationRepo.findById(conversationId).orElse(null);
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

    public void invalidatePendingLoads() {
        requestCounter.incrementAndGet();
    }

    public interface Listener {
        void onLoaded(
                long requestId,
                UUID conversationId,
                List<ConversationRepository.MessageRecord> records,
                ConversationRepository.ConversationRecord conversation
        );

        void onFailure(long requestId, UUID conversationId, Exception error);
    }
}
