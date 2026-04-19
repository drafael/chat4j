package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.provider.api.Message;
import org.apache.commons.lang3.Validate;

import java.util.UUID;

public class AssistantMessageCompletionDispatchCoordinator {

    public boolean handle(
            UUID eventConversationId,
            Message eventMessage,
            UUID currentConversationId,
            CompletionHandler completionHandler,
            FailureHandler failureHandler
    ) {
        Validate.notNull(completionHandler, "completionHandler must not be null");
        Validate.notNull(failureHandler, "failureHandler must not be null");

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
