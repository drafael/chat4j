package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.chat.ChatPanel;
import com.github.drafael.chat4j.provider.api.Message;
import lombok.NonNull;

import java.util.UUID;

public class AssistantMessageCompletionEventDispatchCoordinator {

    private final DispatchAction dispatchAction;

    public AssistantMessageCompletionEventDispatchCoordinator(
            AssistantMessageCompletionDispatchCoordinator assistantMessageCompletionDispatchCoordinator
    ) {
        this(assistantMessageCompletionDispatchCoordinator::handle);
    }

    AssistantMessageCompletionEventDispatchCoordinator(@NonNull DispatchAction dispatchAction) {
        this.dispatchAction = dispatchAction;
    }

    public boolean handle(
            ChatPanel.AssistantMessageEvent event,
            UUID currentConversationId,
            @NonNull AssistantMessageCompletionDispatchCoordinator.CompletionHandler completionHandler,
            @NonNull AssistantMessageCompletionDispatchCoordinator.FailureHandler failureHandler
    ) {

        UUID eventConversationId = event != null ? event.conversationId() : null;
        Message eventMessage = event != null ? event.message() : null;

        return dispatchAction.handle(
                eventConversationId,
                eventMessage,
                currentConversationId,
                completionHandler,
                failureHandler
        );
    }

    @FunctionalInterface
    interface DispatchAction {
        boolean handle(
                UUID eventConversationId,
                Message eventMessage,
                UUID currentConversationId,
                AssistantMessageCompletionDispatchCoordinator.CompletionHandler completionHandler,
                AssistantMessageCompletionDispatchCoordinator.FailureHandler failureHandler
        );
    }
}
