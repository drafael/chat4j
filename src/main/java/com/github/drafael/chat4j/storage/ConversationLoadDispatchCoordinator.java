package com.github.drafael.chat4j.storage;

import lombok.NonNull;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.UUID;

public class ConversationLoadDispatchCoordinator {

    private final EdtDispatcher edtDispatcher;

    public ConversationLoadDispatchCoordinator() {
        this(SwingUtilities::invokeLater);
    }

    ConversationLoadDispatchCoordinator(@NonNull EdtDispatcher edtDispatcher) {
        this.edtDispatcher = edtDispatcher;
    }

    public long dispatch(
            @NonNull UUID conversationId,
            @NonNull AsyncLoader asyncLoader,
            @NonNull LoadedHandler onLoaded,
            @NonNull FailureHandler onFailure
    ) {

        return asyncLoader.loadAsync(conversationId, new ConversationLoadCoordinator.Listener() {
            @Override
            public void onLoaded(
                    long requestId,
                    UUID loadedConversationId,
                    List<ConversationRepo.MessageRecord> records,
                    ConversationRepo.ConversationRecord conversation
            ) {
                edtDispatcher.dispatch(() -> onLoaded.handle(requestId, loadedConversationId, records, conversation));
            }

            @Override
            public void onFailure(long requestId, UUID failedConversationId, Exception error) {
                edtDispatcher.dispatch(() -> onFailure.handle(requestId, failedConversationId, error));
            }
        });
    }

    @FunctionalInterface
    public interface AsyncLoader {
        long loadAsync(UUID conversationId, ConversationLoadCoordinator.Listener listener);
    }

    @FunctionalInterface
    public interface LoadedHandler {
        void handle(
                long requestId,
                UUID conversationId,
                List<ConversationRepo.MessageRecord> records,
                ConversationRepo.ConversationRecord conversation
        );
    }

    @FunctionalInterface
    public interface FailureHandler {
        void handle(long requestId, UUID conversationId, Exception error);
    }

    @FunctionalInterface
    interface EdtDispatcher {
        void dispatch(Runnable action);
    }
}
