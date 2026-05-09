package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.provider.api.Message;
import lombok.NonNull;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

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
            @NonNull List<ConversationRepo.MessageRecord> records,
            ConversationRepo.ConversationRecord conversation,
            @NonNull Consumer<List<Message>> historyLoader,
            @NonNull ConversationLoadApplyCoordinator.PersistedCountMarker persistedCountMarker,
            @NonNull ConversationLoadApplyCoordinator.RenderModeApplier renderModeApplier,
            @NonNull Consumer<String> selectedModelSetter,
            @NonNull Consumer<UUID> conversationSelector
    ) {

        ConversationLoadResultPlanner.LoadedConversationPlan plan =
                planner.planLoaded(requestId, currentConversationId, conversationId, records, conversation);

        return applier.apply(
                plan,
                historyLoader,
                persistedCountMarker,
                renderModeApplier,
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
                List<ConversationRepo.MessageRecord> records,
                ConversationRepo.ConversationRecord conversation
        );
    }

    @FunctionalInterface
    interface Applier {
        boolean apply(
                ConversationLoadResultPlanner.LoadedConversationPlan plan,
                Consumer<List<Message>> historyLoader,
                ConversationLoadApplyCoordinator.PersistedCountMarker persistedCountMarker,
                ConversationLoadApplyCoordinator.RenderModeApplier renderModeApplier,
                Consumer<String> selectedModelSetter,
                Consumer<UUID> conversationSelector
        );
    }
}
