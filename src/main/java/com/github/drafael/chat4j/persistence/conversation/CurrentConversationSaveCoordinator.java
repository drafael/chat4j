package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.support.ModelSelectionCodec.ModelSelection;
import com.github.drafael.chat4j.provider.support.ModelSelectionCodec;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;

public class CurrentConversationSaveCoordinator {

    private final ConversationTitleDeriver conversationTitleDeriver;
    private final ConversationCreator conversationCreator;
    private final HistoryPersister historyPersister;
    private final ConversationAgentSettingsPersister conversationAgentSettingsPersister;
    private final ConversationReasoningLevelPersister conversationReasoningLevelPersister;
    private final ConversationWebSearchSettingsPersister conversationWebSearchSettingsPersister;
    private final ConversationExistsChecker conversationExistsChecker;

    public CurrentConversationSaveCoordinator(
            ConversationTitleDeriver conversationTitleDeriver,
            ConversationPersistenceCoordinator conversationPersistenceCoordinator
    ) {
        this(
                conversationTitleDeriver,
                conversationPersistenceCoordinator::createConversation,
                conversationPersistenceCoordinator::persistConversationHistory,
                conversationPersistenceCoordinator::persistConversationAgentSettings,
                conversationPersistenceCoordinator::persistConversationReasoningLevel,
                conversationPersistenceCoordinator::persistConversationWebSearchSettings,
                conversationPersistenceCoordinator::conversationExists
        );
    }

    CurrentConversationSaveCoordinator(
            @NonNull ConversationTitleDeriver conversationTitleDeriver,
            @NonNull ConversationCreator conversationCreator,
            @NonNull HistoryPersister historyPersister,
            @NonNull ConversationAgentSettingsPersister conversationAgentSettingsPersister,
            @NonNull ConversationReasoningLevelPersister conversationReasoningLevelPersister,
            @NonNull ConversationWebSearchSettingsPersister conversationWebSearchSettingsPersister
    ) {
        this(
                conversationTitleDeriver,
                conversationCreator,
                historyPersister,
                conversationAgentSettingsPersister,
                conversationReasoningLevelPersister,
                conversationWebSearchSettingsPersister,
                conversationId -> true
        );
    }

    CurrentConversationSaveCoordinator(
            @NonNull ConversationTitleDeriver conversationTitleDeriver,
            @NonNull ConversationCreator conversationCreator,
            @NonNull HistoryPersister historyPersister,
            @NonNull ConversationAgentSettingsPersister conversationAgentSettingsPersister,
            @NonNull ConversationReasoningLevelPersister conversationReasoningLevelPersister,
            @NonNull ConversationWebSearchSettingsPersister conversationWebSearchSettingsPersister,
            @NonNull ConversationExistsChecker conversationExistsChecker
    ) {
        this.conversationTitleDeriver = conversationTitleDeriver;
        this.conversationCreator = conversationCreator;
        this.historyPersister = historyPersister;
        this.conversationAgentSettingsPersister = conversationAgentSettingsPersister;
        this.conversationReasoningLevelPersister = conversationReasoningLevelPersister;
        this.conversationWebSearchSettingsPersister = conversationWebSearchSettingsPersister;
        this.conversationExistsChecker = conversationExistsChecker;
    }

    public SaveResult save(
            UUID currentConversationId,
            @NonNull List<Message> history,
            String selectedModelKey,
            ReasoningLevel reasoningLevel,
            boolean agentModeEnabled,
            Path agentProjectRoot,
            boolean webSearchEnabled,
            String webSearchOptionId
    ) throws Exception {

        if (history.isEmpty()) {
            return SaveResult.skippedResult(currentConversationId);
        }

        UUID conversationId = currentConversationId;
        boolean createdConversation = false;

        if (conversationId != null && !conversationExistsChecker.exists(conversationId)) {
            conversationId = null;
        }

        if (conversationId == null) {
            String title = conversationTitleDeriver.derive(history.getFirst());
            ModelSelection modelSelection = ModelSelectionCodec.parse(selectedModelKey)
                    .orElse(new ModelSelection("Unknown", "unknown"));
            conversationId = conversationCreator.create(title, modelSelection.provider(), modelSelection.model());
            conversationAgentSettingsPersister.persist(conversationId, agentModeEnabled, agentProjectRoot);
            conversationReasoningLevelPersister.persist(
                    conversationId,
                    reasoningLevel == null ? ReasoningLevel.OFF : reasoningLevel
            );
            conversationWebSearchSettingsPersister.persist(conversationId, webSearchEnabled, webSearchOptionId);
            createdConversation = true;
        }

        int persistedCount = historyPersister.persist(conversationId, history);
        return persistedCount < history.size()
                ? SaveResult.skippedResult(null)
                : SaveResult.savedResult(conversationId, createdConversation);
    }

    public record SaveResult(boolean saved, UUID conversationId, boolean createdConversation) {

        static SaveResult skippedResult(UUID conversationId) {
            return new SaveResult(false, conversationId, false);
        }

        static SaveResult savedResult(UUID conversationId, boolean createdConversation) {
            return new SaveResult(true, conversationId, createdConversation);
        }
    }

    @FunctionalInterface
    interface ConversationCreator {
        UUID create(String title, String provider, String model) throws Exception;
    }

    @FunctionalInterface
    interface HistoryPersister {
        int persist(UUID conversationId, List<Message> history) throws Exception;
    }

    @FunctionalInterface
    interface ConversationAgentSettingsPersister {
        void persist(UUID conversationId, boolean agentModeEnabled, Path agentProjectRoot) throws Exception;
    }

    @FunctionalInterface
    interface ConversationReasoningLevelPersister {
        void persist(UUID conversationId, ReasoningLevel reasoningLevel) throws Exception;
    }

    @FunctionalInterface
    interface ConversationWebSearchSettingsPersister {
        void persist(UUID conversationId, boolean webSearchEnabled, String webSearchOptionId) throws Exception;
    }

    @FunctionalInterface
    interface ConversationExistsChecker {
        boolean exists(UUID conversationId) throws Exception;
    }
}
