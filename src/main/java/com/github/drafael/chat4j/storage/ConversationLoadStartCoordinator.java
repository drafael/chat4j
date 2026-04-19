package com.github.drafael.chat4j.storage;

import org.apache.commons.lang3.Validate;

import java.util.UUID;
import java.util.function.Consumer;

public class ConversationLoadStartCoordinator {

    private final ConversationLoadDispatchCoordinator conversationLoadDispatchCoordinator;

    public ConversationLoadStartCoordinator(ConversationLoadDispatchCoordinator conversationLoadDispatchCoordinator) {
        this.conversationLoadDispatchCoordinator = Validate.notNull(
                conversationLoadDispatchCoordinator,
                "conversationLoadDispatchCoordinator must not be null"
        );
    }

    public long start(
            UUID conversationId,
            Runnable saveCurrentConversation,
            Consumer<UUID> setCurrentConversationId,
            Runnable clearPendingUnsavedConversationRenderMode,
            Consumer<UUID> setActiveConversationId,
            ConversationLoadDispatchCoordinator.AsyncLoader asyncLoader,
            ConversationLoadDispatchCoordinator.LoadedHandler onLoaded,
            ConversationLoadDispatchCoordinator.FailureHandler onFailure
    ) {
        Validate.notNull(conversationId, "conversationId must not be null");
        Validate.notNull(saveCurrentConversation, "saveCurrentConversation must not be null");
        Validate.notNull(setCurrentConversationId, "setCurrentConversationId must not be null");
        Validate.notNull(
                clearPendingUnsavedConversationRenderMode,
                "clearPendingUnsavedConversationRenderMode must not be null"
        );
        Validate.notNull(setActiveConversationId, "setActiveConversationId must not be null");
        Validate.notNull(asyncLoader, "asyncLoader must not be null");
        Validate.notNull(onLoaded, "onLoaded must not be null");
        Validate.notNull(onFailure, "onFailure must not be null");

        saveCurrentConversation.run();
        setCurrentConversationId.accept(conversationId);
        clearPendingUnsavedConversationRenderMode.run();
        setActiveConversationId.accept(conversationId);

        return conversationLoadDispatchCoordinator.dispatch(conversationId, asyncLoader, onLoaded, onFailure);
    }
}
