package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.provider.api.Message;
import org.apache.commons.lang3.Validate;

import java.util.UUID;
import java.util.function.Consumer;

public class AssistantMessageCompletionFlowCoordinator {

    private final AssistantMessageCompletionCoordinator assistantMessageCompletionCoordinator;

    public AssistantMessageCompletionFlowCoordinator(AssistantMessageCompletionCoordinator assistantMessageCompletionCoordinator) {
        this.assistantMessageCompletionCoordinator = Validate.notNull(
                assistantMessageCompletionCoordinator,
                "assistantMessageCompletionCoordinator must not be null"
        );
    }

    public boolean handle(
            UUID eventConversationId,
            Message eventMessage,
            UUID currentConversationId,
            Runnable refreshSidebar,
            Consumer<UUID> selectConversation
    ) throws Exception {
        Validate.notNull(refreshSidebar, "refreshSidebar must not be null");
        Validate.notNull(selectConversation, "selectConversation must not be null");

        AssistantMessageCompletionCoordinator.PersistResult persistResult =
                assistantMessageCompletionCoordinator.persist(eventConversationId, eventMessage, currentConversationId);
        if (!persistResult.handled()) {
            return false;
        }

        refreshSidebar.run();
        if (persistResult.conversationIdToSelect() != null) {
            selectConversation.accept(persistResult.conversationIdToSelect());
        }
        return true;
    }
}
