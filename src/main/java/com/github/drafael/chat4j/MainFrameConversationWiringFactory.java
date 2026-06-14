package com.github.drafael.chat4j;

import com.github.drafael.chat4j.persistence.conversation.AssistantMessageCompletionCoordinator;
import com.github.drafael.chat4j.persistence.conversation.AssistantMessageCompletionFlowCoordinator;
import com.github.drafael.chat4j.persistence.conversation.ConversationLoadApplyCoordinator;
import com.github.drafael.chat4j.persistence.conversation.ConversationLoadApplyDispatchCoordinator;
import com.github.drafael.chat4j.persistence.conversation.ConversationLoadCoordinator;
import com.github.drafael.chat4j.persistence.conversation.ConversationLoadResultPlanner;
import com.github.drafael.chat4j.persistence.conversation.ConversationPersistenceCoordinator;
import com.github.drafael.chat4j.persistence.conversation.ConversationRepository;
import com.github.drafael.chat4j.persistence.conversation.ConversationTitleDeriver;
import com.github.drafael.chat4j.persistence.conversation.CurrentConversationSaveCoordinator;
import com.github.drafael.chat4j.persistence.conversation.PersistedMessageCounter;
import lombok.NonNull;

public class MainFrameConversationWiringFactory {

    public ConversationWiring create(
            @NonNull ConversationRepository conversationRepo,
            @NonNull PersistedMessageCounter persistedMessageCounter
    ) {
        var conversationLoadCoordinator = new ConversationLoadCoordinator(conversationRepo);
        var conversationLoadResultPlanner = new ConversationLoadResultPlanner(conversationLoadCoordinator::isCurrentRequest);
        var conversationLoadApplyDispatchCoordinator = new ConversationLoadApplyDispatchCoordinator(
                conversationLoadResultPlanner,
                new ConversationLoadApplyCoordinator()
        );
        var conversationPersistenceCoordinator = new ConversationPersistenceCoordinator(
                conversationRepo,
                persistedMessageCounter
        );
        var assistantMessageCompletionFlowCoordinator = new AssistantMessageCompletionFlowCoordinator(
                new AssistantMessageCompletionCoordinator(conversationPersistenceCoordinator)
        );
        var currentConversationSaveCoordinator = new CurrentConversationSaveCoordinator(
                new ConversationTitleDeriver(),
                conversationPersistenceCoordinator
        );

        return new ConversationWiring(
                conversationLoadCoordinator,
                conversationLoadResultPlanner,
                conversationLoadApplyDispatchCoordinator,
                conversationPersistenceCoordinator,
                assistantMessageCompletionFlowCoordinator,
                currentConversationSaveCoordinator
        );
    }

    public record ConversationWiring(
            ConversationLoadCoordinator conversationLoadCoordinator,
            ConversationLoadResultPlanner conversationLoadResultPlanner,
            ConversationLoadApplyDispatchCoordinator conversationLoadApplyDispatchCoordinator,
            ConversationPersistenceCoordinator conversationPersistenceCoordinator,
            AssistantMessageCompletionFlowCoordinator assistantMessageCompletionFlowCoordinator,
            CurrentConversationSaveCoordinator currentConversationSaveCoordinator
    ) {
    }
}
