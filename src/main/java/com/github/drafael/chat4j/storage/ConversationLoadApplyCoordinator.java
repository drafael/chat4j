package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.provider.api.Message;
import lombok.NonNull;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public class ConversationLoadApplyCoordinator {

    public boolean apply(
            @NonNull ConversationLoadResultPlanner.LoadedConversationPlan plan,
            @NonNull Consumer<List<Message>> historyLoader,
            @NonNull PersistedCountMarker persistedCountMarker,
            @NonNull Consumer<String> selectedModelSetter,
            @NonNull Consumer<UUID> conversationSelector
    ) {

        if (plan.ignore()) {
            return false;
        }

        historyLoader.accept(plan.messages());
        persistedCountMarker.mark(plan.conversationId(), plan.persistedCount());
        if (plan.selectedModelKey() != null) {
            selectedModelSetter.accept(plan.selectedModelKey());
        }
        conversationSelector.accept(plan.conversationId());
        return true;
    }

    @FunctionalInterface
    public interface PersistedCountMarker {
        void mark(UUID conversationId, int persistedCount);
    }
}
