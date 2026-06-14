package com.github.drafael.chat4j.persistence.conversation;

import java.util.UUID;
import java.util.function.Consumer;
import lombok.NonNull;

public class ConversationLoadStartCoordinator {

    private final ConversationLoadDispatchCoordinator conversationLoadDispatchCoordinator;

    public ConversationLoadStartCoordinator(@NonNull ConversationLoadDispatchCoordinator conversationLoadDispatchCoordinator) {
        this.conversationLoadDispatchCoordinator = conversationLoadDispatchCoordinator;
    }

    public long start(
            @NonNull UUID conversationId,
            @NonNull Runnable saveCurrentConversation,
            @NonNull Consumer<UUID> setCurrentConversationId,
            @NonNull Consumer<UUID> setActiveConversationId,
            @NonNull ConversationLoadDispatchCoordinator.AsyncLoader asyncLoader,
            @NonNull ConversationLoadDispatchCoordinator.LoadedHandler onLoaded,
            @NonNull ConversationLoadDispatchCoordinator.FailureHandler onFailure
    ) {

        saveCurrentConversation.run();
        setCurrentConversationId.accept(conversationId);
        setActiveConversationId.accept(conversationId);

        return conversationLoadDispatchCoordinator.dispatch(conversationId, asyncLoader, onLoaded, onFailure);
    }
}
