package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.provider.api.Message;
import org.apache.commons.lang3.Validate;

import java.util.List;
import java.util.UUID;

public class CurrentConversationSaveDispatchCoordinator {

    public boolean save(
            UUID currentConversationId,
            AssistantRenderMode pendingUnsavedConversationRenderMode,
            List<Message> history,
            String selectedModelKey,
            AssistantRenderMode currentAssistantRenderMode,
            SaveAction saveAction,
            UiApplyAction uiApplyAction,
            FailureHandler failureHandler
    ) {
        Validate.notNull(history, "history must not be null");
        Validate.notNull(currentAssistantRenderMode, "currentAssistantRenderMode must not be null");
        Validate.notNull(saveAction, "saveAction must not be null");
        Validate.notNull(uiApplyAction, "uiApplyAction must not be null");
        Validate.notNull(failureHandler, "failureHandler must not be null");

        try {
            CurrentConversationSaveCoordinator.SaveResult saveResult = saveAction.save(
                    currentConversationId,
                    pendingUnsavedConversationRenderMode,
                    history,
                    selectedModelKey,
                    currentAssistantRenderMode
            );
            return uiApplyAction.apply(saveResult);
        } catch (Exception e) {
            failureHandler.handle(e);
            return false;
        }
    }

    @FunctionalInterface
    public interface SaveAction {
        CurrentConversationSaveCoordinator.SaveResult save(
                UUID currentConversationId,
                AssistantRenderMode pendingUnsavedConversationRenderMode,
                List<Message> history,
                String selectedModelKey,
                AssistantRenderMode currentAssistantRenderMode
        ) throws Exception;
    }

    @FunctionalInterface
    public interface UiApplyAction {
        boolean apply(CurrentConversationSaveCoordinator.SaveResult saveResult);
    }

    @FunctionalInterface
    public interface FailureHandler {
        void handle(Exception error);
    }
}
