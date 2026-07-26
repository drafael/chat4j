package com.github.drafael.chat4j;

import com.github.drafael.chat4j.persistence.conversation.ConversationLoadApplyCoordinator;
import com.github.drafael.chat4j.persistence.conversation.ConversationLoadApplyDispatchCoordinator;
import com.github.drafael.chat4j.persistence.conversation.ConversationLoadCoordinator;
import com.github.drafael.chat4j.persistence.conversation.ConversationLoadResultPlanner;
import com.github.drafael.chat4j.persistence.conversation.ConversationPersistenceCoordinator;
import com.github.drafael.chat4j.persistence.conversation.ConversationRepository;
import lombok.NonNull;

public class MainFrameConversationWiringFactory {

    public ConversationWiring create(@NonNull ConversationRepository conversationRepo) {
        var conversationPersistenceCoordinator = new ConversationPersistenceCoordinator(conversationRepo);
        var conversationLoadCoordinator = new ConversationLoadCoordinator(
                conversationRepo,
                conversationPersistenceCoordinator
        );
        var conversationLoadResultPlanner = new ConversationLoadResultPlanner(conversationLoadCoordinator::isCurrentRequest);
        var conversationLoadApplyDispatchCoordinator = new ConversationLoadApplyDispatchCoordinator(
                conversationLoadResultPlanner,
                new ConversationLoadApplyCoordinator()
        );
        return new ConversationWiring(
                conversationLoadCoordinator,
                conversationLoadResultPlanner,
                conversationLoadApplyDispatchCoordinator,
                conversationPersistenceCoordinator
        );
    }

    public record ConversationWiring(
            ConversationLoadCoordinator conversationLoadCoordinator,
            ConversationLoadResultPlanner conversationLoadResultPlanner,
            ConversationLoadApplyDispatchCoordinator conversationLoadApplyDispatchCoordinator,
            ConversationPersistenceCoordinator conversationPersistenceCoordinator
    ) {
    }
}
