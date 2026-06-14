package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.provider.api.Message;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.NonNull;

public class AssistantMessageCompletionFlowCoordinator {

    private final AssistantMessageCompletionCoordinator assistantMessageCompletionCoordinator;

    public AssistantMessageCompletionFlowCoordinator(@NonNull AssistantMessageCompletionCoordinator assistantMessageCompletionCoordinator) {
        this.assistantMessageCompletionCoordinator = assistantMessageCompletionCoordinator;
    }

    public boolean handle(
            UUID eventConversationId,
            Message eventMessage,
            UUID currentConversationId,
            @NonNull Runnable refreshSidebar,
            @NonNull Consumer<UUID> selectConversation
    ) throws Exception  {

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
