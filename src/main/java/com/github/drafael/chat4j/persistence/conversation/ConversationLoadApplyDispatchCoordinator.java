package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.provider.api.Message;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.NonNull;

public class ConversationLoadApplyDispatchCoordinator {

    private final Planner planner;
    private final Applier applier;

    public ConversationLoadApplyDispatchCoordinator(
            ConversationLoadResultPlanner conversationLoadResultPlanner,
            ConversationLoadApplyCoordinator conversationLoadApplyCoordinator
    ) {
        this(
                conversationLoadResultPlanner::planLoaded,
                conversationLoadApplyCoordinator::apply
        );
    }

    ConversationLoadApplyDispatchCoordinator(@NonNull Planner planner, @NonNull Applier applier) {
        this.planner = planner;
        this.applier = applier;
    }

    public boolean applyLoaded(
            long requestId,
            UUID currentConversationId,
            @NonNull UUID conversationId,
            @NonNull List<ConversationRepository.MessageRecord> records,
            ConversationRepository.ConversationRecord conversation,
            @NonNull Consumer<List<Message>> historyLoader,
            @NonNull ConversationLoadApplyCoordinator.PersistedCountMarker persistedCountMarker,
            @NonNull Consumer<String> selectedModelSetter,
            @NonNull Consumer<UUID> conversationSelector
    ) {

        ConversationLoadResultPlanner.LoadedConversationPlan plan =
                planner.planLoaded(requestId, currentConversationId, conversationId, records, conversation);

        return applier.apply(
                plan,
                historyLoader,
                persistedCountMarker,
                selectedModelSetter,
                conversationSelector
        );
    }

    @FunctionalInterface
    interface Planner {
        ConversationLoadResultPlanner.LoadedConversationPlan planLoaded(
                long requestId,
                UUID activeConversationId,
                UUID loadedConversationId,
                List<ConversationRepository.MessageRecord> records,
                ConversationRepository.ConversationRecord conversation
        );
    }

    @FunctionalInterface
    interface Applier {
        boolean apply(
                ConversationLoadResultPlanner.LoadedConversationPlan plan,
                Consumer<List<Message>> historyLoader,
                ConversationLoadApplyCoordinator.PersistedCountMarker persistedCountMarker,
                Consumer<String> selectedModelSetter,
                Consumer<UUID> conversationSelector
        );
    }
}
