package com.github.drafael.chat4j.persistence.conversation;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.NonNull;

public class ConversationLoadApplyCoordinator {

    public boolean apply(
            @NonNull ConversationLoadResultPlanner.LoadedConversationPlan plan,
            @NonNull Consumer<List<ConversationRepository.MessageRecord>> historyLoader,
            @NonNull Consumer<String> selectedModelSetter,
            @NonNull Consumer<UUID> conversationSelector
    ) {

        if (plan.ignore()) {
            return false;
        }

        conversationSelector.accept(plan.conversationId());
        if (plan.selectedModelKey() != null) {
            selectedModelSetter.accept(plan.selectedModelKey());
        }
        historyLoader.accept(plan.records());
        return true;
    }
}
