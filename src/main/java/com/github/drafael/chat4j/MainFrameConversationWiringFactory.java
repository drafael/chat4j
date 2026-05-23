package com.github.drafael.chat4j;

import com.github.drafael.chat4j.storage.AssistantMessageCompletionCoordinator;
import com.github.drafael.chat4j.storage.AssistantMessageCompletionFlowCoordinator;
import com.github.drafael.chat4j.storage.ConversationLoadApplyCoordinator;
import com.github.drafael.chat4j.storage.ConversationLoadApplyDispatchCoordinator;
import com.github.drafael.chat4j.storage.ConversationLoadCoordinator;
import com.github.drafael.chat4j.storage.ConversationLoadResultPlanner;
import com.github.drafael.chat4j.storage.ConversationPersistenceCoordinator;
import com.github.drafael.chat4j.storage.ConversationRepo;
import com.github.drafael.chat4j.storage.ConversationTitleDeriver;
import com.github.drafael.chat4j.storage.CurrentConversationSaveCoordinator;
import com.github.drafael.chat4j.storage.PersistedMessageCounter;
import lombok.NonNull;

public class MainFrameConversationWiringFactory {

    public ConversationWiring create(
            @NonNull ConversationRepo conversationRepo,
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
