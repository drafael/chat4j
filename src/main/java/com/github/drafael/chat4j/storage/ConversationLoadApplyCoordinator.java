package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.provider.api.Message;
import org.apache.commons.lang3.Validate;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public class ConversationLoadApplyCoordinator {

    public boolean apply(
            ConversationLoadResultPlanner.LoadedConversationPlan plan,
            Consumer<List<Message>> historyLoader,
            PersistedCountMarker persistedCountMarker,
            RenderModeApplier renderModeApplier,
            Consumer<String> selectedModelSetter,
            Consumer<UUID> conversationSelector
    ) {
        Validate.notNull(plan, "plan must not be null");
        Validate.notNull(historyLoader, "historyLoader must not be null");
        Validate.notNull(persistedCountMarker, "persistedCountMarker must not be null");
        Validate.notNull(renderModeApplier, "renderModeApplier must not be null");
        Validate.notNull(selectedModelSetter, "selectedModelSetter must not be null");
        Validate.notNull(conversationSelector, "conversationSelector must not be null");

        if (plan.ignore()) {
            return false;
        }

        historyLoader.accept(plan.messages());
        persistedCountMarker.mark(plan.conversationId(), plan.persistedCount());
        renderModeApplier.apply(plan.assistantRenderMode(), true);
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

    @FunctionalInterface
    public interface RenderModeApplier {
        void apply(AssistantRenderMode mode, boolean userInitiated);
    }
}
