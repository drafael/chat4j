package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.support.ModelSelectionCodec;
import com.github.drafael.chat4j.provider.support.ModelSelectionCodec.ModelSelection;
import com.github.drafael.chat4j.settings.AssistantRenderModeSettingsCoordinator;
import lombok.NonNull;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public class CurrentConversationSaveCoordinator {

    private final ConversationTitleDeriver conversationTitleDeriver;
    private final ConversationCreator conversationCreator;
    private final HistoryPersister historyPersister;
    private final ConversationModePersister conversationModePersister;
    private final ConversationAgentSettingsPersister conversationAgentSettingsPersister;
    private final ConversationReasoningLevelPersister conversationReasoningLevelPersister;

    public CurrentConversationSaveCoordinator(
            ConversationTitleDeriver conversationTitleDeriver,
            ConversationPersistenceCoordinator conversationPersistenceCoordinator,
            AssistantRenderModeSettingsCoordinator assistantRenderModeSettingsCoordinator
    ) {
        this(
                conversationTitleDeriver,
                conversationPersistenceCoordinator::createConversation,
                conversationPersistenceCoordinator::persistConversationHistory,
                assistantRenderModeSettingsCoordinator::persistConversationMode,
                conversationPersistenceCoordinator::persistConversationAgentSettings,
                conversationPersistenceCoordinator::persistConversationReasoningLevel
        );
    }

    CurrentConversationSaveCoordinator(
            @NonNull ConversationTitleDeriver conversationTitleDeriver,
            @NonNull ConversationCreator conversationCreator,
            @NonNull HistoryPersister historyPersister,
            @NonNull ConversationModePersister conversationModePersister,
            @NonNull ConversationAgentSettingsPersister conversationAgentSettingsPersister,
            @NonNull ConversationReasoningLevelPersister conversationReasoningLevelPersister
    ) {
        this.conversationTitleDeriver = conversationTitleDeriver;
        this.conversationCreator = conversationCreator;
        this.historyPersister = historyPersister;
        this.conversationModePersister = conversationModePersister;
        this.conversationAgentSettingsPersister = conversationAgentSettingsPersister;
        this.conversationReasoningLevelPersister = conversationReasoningLevelPersister;
    }

    public SaveResult save(
            UUID currentConversationId,
            AssistantRenderMode pendingUnsavedConversationRenderMode,
            @NonNull List<Message> history,
            String selectedModelKey,
            @NonNull AssistantRenderMode currentAssistantRenderMode,
            ReasoningLevel reasoningLevel,
            boolean agentModeEnabled,
            Path agentProjectRoot
    ) throws Exception  {

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
            conversationAgentSettingsPersister.persist(conversationId, agentModeEnabled, agentProjectRoot);
            conversationReasoningLevelPersister.persist(
                    conversationId,
                    reasoningLevel == null ? ReasoningLevel.OFF : reasoningLevel
            );
            pendingUnsavedMode = null;
            createdConversation = true;
        }

        historyPersister.persist(conversationId, history);
        return SaveResult.savedResult(conversationId, pendingUnsavedMode, createdConversation);
    }

    public record SaveResult(
            boolean saved,
            @NonNull UUID conversationId,
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

    @FunctionalInterface
    interface ConversationAgentSettingsPersister {
        void persist(UUID conversationId, boolean agentModeEnabled, Path agentProjectRoot) throws Exception;
    }

    @FunctionalInterface
    interface ConversationReasoningLevelPersister {
        void persist(UUID conversationId, ReasoningLevel reasoningLevel) throws Exception;
    }
}
