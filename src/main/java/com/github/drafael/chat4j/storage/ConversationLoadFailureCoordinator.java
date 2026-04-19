package com.github.drafael.chat4j.storage;

import org.apache.commons.lang3.Validate;

import java.util.UUID;

public class ConversationLoadFailureCoordinator {

    public boolean handle(
            long requestId,
            UUID currentConversationId,
            UUID failedConversationId,
            Exception error,
            FailureGuard failureGuard,
            ErrorPresenter errorPresenter
    ) {
        Validate.notNull(error, "error must not be null");
        Validate.notNull(failureGuard, "failureGuard must not be null");
        Validate.notNull(errorPresenter, "errorPresenter must not be null");

        if (!failureGuard.shouldHandle(requestId, currentConversationId, failedConversationId)) {
            return false;
        }

        errorPresenter.show("Failed to load conversation: %s".formatted(error.getMessage()));
        return true;
    }

    @FunctionalInterface
    public interface FailureGuard {
        boolean shouldHandle(long requestId, UUID currentConversationId, UUID failedConversationId);
    }

    @FunctionalInterface
    public interface ErrorPresenter {
        void show(String message);
    }
}
