package com.github.drafael.chat4j.storage;


import lombok.NonNull;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class ConversationLoadCoordinator {

    private final ConversationRepo conversationRepo;
    private final AtomicLong requestCounter = new AtomicLong();

    public ConversationLoadCoordinator(@NonNull ConversationRepo conversationRepo) {
        this.conversationRepo = conversationRepo;
    }

    public long loadAsync(@NonNull UUID conversationId, @NonNull Listener listener) {

        long requestId = requestCounter.incrementAndGet();
        Thread.startVirtualThread(() -> {
            try {
                List<ConversationRepo.MessageRecord> records = conversationRepo.getMessages(conversationId);
                ConversationRepo.ConversationRecord conversation = conversationRepo.findById(conversationId).orElse(null);
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
                ConversationRepo.ConversationRecord conversation
        );

        void onFailure(long requestId, UUID conversationId, Exception error);
    }
}
