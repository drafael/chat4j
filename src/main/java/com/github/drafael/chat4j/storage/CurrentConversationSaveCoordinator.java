package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.support.ModelSelectionCodec;
import com.github.drafael.chat4j.provider.support.ModelSelectionCodec.ModelSelection;
import com.github.drafael.chat4j.settings.AssistantRenderModeSettingsCoordinator;
import org.apache.commons.lang3.Validate;

import java.util.List;
import java.util.UUID;

public class CurrentConversationSaveCoordinator {

    private final ConversationTitleDeriver conversationTitleDeriver;
    private final ConversationCreator conversationCreator;
    private final HistoryPersister historyPersister;
    private final ConversationModePersister conversationModePersister;

    public CurrentConversationSaveCoordinator(
            ConversationTitleDeriver conversationTitleDeriver,
            ConversationPersistenceCoordinator conversationPersistenceCoordinator,
            AssistantRenderModeSettingsCoordinator assistantRenderModeSettingsCoordinator
    ) {
        this(
                conversationTitleDeriver,
                conversationPersistenceCoordinator::createConversation,
                conversationPersistenceCoordinator::persistConversationHistory,
                assistantRenderModeSettingsCoordinator::persistConversationMode
        );
    }

    CurrentConversationSaveCoordinator(
            ConversationTitleDeriver conversationTitleDeriver,
            ConversationCreator conversationCreator,
            HistoryPersister historyPersister,
            ConversationModePersister conversationModePersister
    ) {
        this.conversationTitleDeriver = Validate.notNull(
                conversationTitleDeriver,
                "conversationTitleDeriver must not be null"
        );
        this.conversationCreator = Validate.notNull(conversationCreator, "conversationCreator must not be null");
        this.historyPersister = Validate.notNull(historyPersister, "historyPersister must not be null");
        this.conversationModePersister = Validate.notNull(
                conversationModePersister,
                "conversationModePersister must not be null"
        );
    }

    public SaveResult save(
            UUID currentConversationId,
            AssistantRenderMode pendingUnsavedConversationRenderMode,
            List<Message> history,
            String selectedModelKey,
            AssistantRenderMode currentAssistantRenderMode
    ) throws Exception {
        Validate.notNull(history, "history must not be null");
        Validate.notNull(currentAssistantRenderMode, "currentAssistantRenderMode must not be null");

        if (history.isEmpty()) {
            return SaveResult.skippedResult(currentConversationId, pendingUnsavedConversationRenderMode);
        }

        UUID conversationId = currentConversationId;
        AssistantRenderMode pendingUnsavedMode = pendingUnsavedConversationRenderMode;
        boolean createdConversation = false;

        if (conversationId == null) {
            String title = conversationTitleDeriver.derive(history.getFirst());
            ModelSelection modelSelection = ModelSelectionCodec.parse(selectedModelKey)
                    .orElse(new ModelSelection("Unknown", "unknown"));
            conversationId = conversationCreator.create(title, modelSelection.provider(), modelSelection.model());

            AssistantRenderMode modeToPersist = pendingUnsavedMode != null
                    ? pendingUnsavedMode
                    : currentAssistantRenderMode;
            conversationModePersister.persist(conversationId, modeToPersist);
            pendingUnsavedMode = null;
            createdConversation = true;
        }

        historyPersister.persist(conversationId, history);
        return SaveResult.savedResult(conversationId, pendingUnsavedMode, createdConversation);
    }

    public record SaveResult(
            boolean saved,
            UUID conversationId,
            AssistantRenderMode pendingUnsavedConversationRenderMode,
            boolean createdConversation
    ) {

        static SaveResult skippedResult(UUID conversationId, AssistantRenderMode pendingUnsavedConversationRenderMode) {
            return new SaveResult(false, conversationId, pendingUnsavedConversationRenderMode, false);
        }

        static SaveResult savedResult(
                UUID conversationId,
                AssistantRenderMode pendingUnsavedConversationRenderMode,
                boolean createdConversation
        ) {
            Validate.notNull(conversationId, "conversationId must not be null");
            return new SaveResult(true, conversationId, pendingUnsavedConversationRenderMode, createdConversation);
        }
    }

    @FunctionalInterface
    interface ConversationCreator {
        UUID create(String title, String provider, String model) throws Exception;
    }

    @FunctionalInterface
    interface HistoryPersister {
        void persist(UUID conversationId, List<Message> history) throws Exception;
    }

    @FunctionalInterface
    interface ConversationModePersister {
        void persist(UUID conversationId, AssistantRenderMode mode);
    }
}
