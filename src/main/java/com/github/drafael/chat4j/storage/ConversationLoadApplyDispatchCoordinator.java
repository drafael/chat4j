package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.provider.api.Message;
import org.apache.commons.lang3.Validate;

import java.util.List;
import java.util.Optional;
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

    ConversationLoadApplyDispatchCoordinator(Planner planner, Applier applier) {
        this.planner = Validate.notNull(planner, "planner must not be null");
        this.applier = Validate.notNull(applier, "applier must not be null");
    }

    public boolean applyLoaded(
            long requestId,
            UUID currentConversationId,
            UUID conversationId,
            List<ConversationRepo.MessageRecord> records,
            Optional<ConversationRepo.ConversationRecord> conversation,
            Consumer<List<Message>> historyLoader,
            ConversationLoadApplyCoordinator.PersistedCountMarker persistedCountMarker,
            ConversationLoadApplyCoordinator.RenderModeApplier renderModeApplier,
            Consumer<String> selectedModelSetter,
            Consumer<UUID> conversationSelector
    ) {
        Validate.notNull(conversationId, "conversationId must not be null");
        Validate.notNull(records, "records must not be null");
        Validate.notNull(conversation, "conversation must not be null");
        Validate.notNull(historyLoader, "historyLoader must not be null");
        Validate.notNull(persistedCountMarker, "persistedCountMarker must not be null");
        Validate.notNull(renderModeApplier, "renderModeApplier must not be null");
        Validate.notNull(selectedModelSetter, "selectedModelSetter must not be null");
        Validate.notNull(conversationSelector, "conversationSelector must not be null");

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
                Optional<ConversationRepo.ConversationRecord> conversation
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
