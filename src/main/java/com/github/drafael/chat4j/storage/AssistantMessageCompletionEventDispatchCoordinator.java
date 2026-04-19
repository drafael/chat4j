package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.chat.ChatPanel;
import com.github.drafael.chat4j.provider.api.Message;
import org.apache.commons.lang3.Validate;

import java.util.UUID;

public class AssistantMessageCompletionEventDispatchCoordinator {

    private final DispatchAction dispatchAction;

    public AssistantMessageCompletionEventDispatchCoordinator(
            AssistantMessageCompletionDispatchCoordinator assistantMessageCompletionDispatchCoordinator
    ) {
        this(assistantMessageCompletionDispatchCoordinator::handle);
    }

    AssistantMessageCompletionEventDispatchCoordinator(DispatchAction dispatchAction) {
        this.dispatchAction = Validate.notNull(dispatchAction, "dispatchAction must not be null");
    }

    public boolean handle(
            ChatPanel.AssistantMessageEvent event,
            UUID currentConversationId,
            AssistantMessageCompletionDispatchCoordinator.CompletionHandler completionHandler,
            AssistantMessageCompletionDispatchCoordinator.FailureHandler failureHandler
    ) {
        Validate.notNull(completionHandler, "completionHandler must not be null");
        Validate.notNull(failureHandler, "failureHandler must not be null");

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
