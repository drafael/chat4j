package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.provider.api.Message;
import lombok.NonNull;

import java.util.UUID;

public class AssistantMessageCompletionDispatchCoordinator {

    public boolean handle(
            UUID eventConversationId,
            Message eventMessage,
            UUID currentConversationId,
            @NonNull CompletionHandler completionHandler,
            @NonNull FailureHandler failureHandler
    ) {

        try {
            return completionHandler.handle(eventConversationId, eventMessage, currentConversationId);
        } catch (Exception e) {
            failureHandler.handle(eventConversationId, e);
            return false;
        }
    }

    @FunctionalInterface
    public interface CompletionHandler {
        boolean handle(UUID eventConversationId, Message eventMessage, UUID currentConversationId) throws Exception;
    }

    @FunctionalInterface
    public interface FailureHandler {
        void handle(UUID eventConversationId, Exception error);
    }
}
