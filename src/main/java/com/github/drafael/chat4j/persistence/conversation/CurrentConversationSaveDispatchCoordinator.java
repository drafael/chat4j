package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;

public class CurrentConversationSaveDispatchCoordinator {

    public void save(
            UUID currentConversationId,
            @NonNull List<Message> history,
            String selectedModelKey,
            ReasoningLevel reasoningLevel,
            boolean agentModeEnabled,
            Path agentProjectRoot,
            @NonNull SaveAction saveAction,
            @NonNull UiApplyAction uiApplyAction,
            @NonNull FailureHandler failureHandler
    ) {
        try {
            CurrentConversationSaveCoordinator.SaveResult saveResult = saveAction.save(
                    currentConversationId,
                    history,
                    selectedModelKey,
                    reasoningLevel,
                    agentModeEnabled,
                    agentProjectRoot
            );
            uiApplyAction.apply(saveResult);
        } catch (Exception e) {
            failureHandler.handle(e);
        }
    }

    @FunctionalInterface
    public interface SaveAction {
        CurrentConversationSaveCoordinator.SaveResult save(
                UUID currentConversationId,
                @NonNull List<Message> history,
                String selectedModelKey,
                ReasoningLevel reasoningLevel,
                boolean agentModeEnabled,
                Path agentProjectRoot
        ) throws Exception;
    }

    @FunctionalInterface
    public interface UiApplyAction {
        void apply(@NonNull CurrentConversationSaveCoordinator.SaveResult saveResult);
    }

    @FunctionalInterface
    public interface FailureHandler {
        void handle(@NonNull Exception error);
    }
}
