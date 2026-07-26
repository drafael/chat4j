package com.github.drafael.chat4j.persistence.conversation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NonNull;

public class ConversationLoadCoordinator {

    private final ConversationRepository conversationRepo;
    private final ConversationPersistenceCoordinator persistenceCoordinator;
    private final AtomicLong requestCounter = new AtomicLong();
    private final AtomicReference<LoadStamp> latestLoadStamp = new AtomicReference<>();

    public ConversationLoadCoordinator(
            @NonNull ConversationRepository conversationRepo,
            @NonNull ConversationPersistenceCoordinator persistenceCoordinator
    ) {
        this.conversationRepo = conversationRepo;
        this.persistenceCoordinator = persistenceCoordinator;
    }

    public long loadAsync(@NonNull UUID conversationId, @NonNull Listener listener) {

        long requestId = requestCounter.incrementAndGet();
        Thread.startVirtualThread(() -> {
            try {
                while (true) {
                    long fencedRevision = fenceAndCaptureRevision(conversationId);
                    Optional<ConversationRepository.LoadedConversation> loaded = conversationRepo.loadConversation(conversationId);
                    if (isCurrentRequest(requestId)
                            && persistenceCoordinator.revision(conversationId) != fencedRevision
                    ) {
                        continue;
                    }
                    ConversationRepository.LoadedConversation loadedConversation = loaded.orElseThrow(() ->
                            new ConversationNotFoundException(conversationId)
                    );
                    List<ConversationRepository.MessageRecord> records = loadedConversation.messages();
                    ConversationRepository.ConversationRecord conversation = loadedConversation.conversation();
                    if (requestId == requestCounter.get()) {
                        latestLoadStamp.set(new LoadStamp(requestId, conversationId, fencedRevision));
                    }
                    listener.onLoaded(requestId, conversationId, records, conversation);
                    return;
                }
            } catch (Exception e) {
                listener.onFailure(requestId, conversationId, e);
            }
        });

        return requestId;
    }

    private long fenceAndCaptureRevision(UUID conversationId) {
        return persistenceCoordinator.fenceRevision(conversationId).join();
    }

    public boolean isCurrentRequest(long requestId) {
        if (requestId != requestCounter.get()) {
            return false;
        }
        LoadStamp stamp = latestLoadStamp.get();
        return stamp == null
                || stamp.requestId() != requestId
                || persistenceCoordinator.revision(stamp.conversationId()) == stamp.revision();
    }

    public boolean mutationChangedSinceRead(long requestId, UUID conversationId) {
        LoadStamp stamp = latestLoadStamp.get();
        return requestId == requestCounter.get()
                && stamp != null
                && stamp.requestId() == requestId
                && stamp.conversationId().equals(conversationId)
                && persistenceCoordinator.revision(conversationId) != stamp.revision();
    }

    public void invalidatePendingLoads() {
        requestCounter.incrementAndGet();
    }

    private record LoadStamp(long requestId, UUID conversationId, long revision) {
    }

    public static final class ConversationNotFoundException extends Exception {
        public ConversationNotFoundException(UUID conversationId) {
            super("Conversation no longer exists: %s".formatted(conversationId));
        }
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
