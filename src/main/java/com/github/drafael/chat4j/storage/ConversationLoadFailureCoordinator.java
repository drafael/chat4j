package com.github.drafael.chat4j.storage;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.util.UUID;

@Slf4j
public class ConversationLoadFailureCoordinator {

    public boolean handle(
            long requestId,
            UUID currentConversationId,
            UUID failedConversationId,
            @NonNull Exception error,
            @NonNull FailureGuard failureGuard,
            @NonNull ErrorPresenter errorPresenter
    ) {

        if (!failureGuard.shouldHandle(requestId, currentConversationId, failedConversationId)) {
            return false;
        }

        log.warn("Failed to load conversation {}: {}", failedConversationId, ExceptionUtils.getMessage(error));
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
