package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import org.apache.commons.lang3.Validate;

import java.util.UUID;
import java.util.function.Consumer;

public class CurrentConversationSaveUiApplyCoordinator {

    public boolean apply(
            CurrentConversationSaveCoordinator.SaveResult saveResult,
            Consumer<UUID> setCurrentConversationId,
            Consumer<AssistantRenderMode> setPendingUnsavedConversationRenderMode,
            Consumer<UUID> setActiveConversationId,
            Runnable refreshSidebar,
            Consumer<UUID> selectConversation,
            boolean selectCreatedConversation
    ) {
        Validate.notNull(saveResult, "saveResult must not be null");
        Validate.notNull(setCurrentConversationId, "setCurrentConversationId must not be null");
        Validate.notNull(
                setPendingUnsavedConversationRenderMode,
                "setPendingUnsavedConversationRenderMode must not be null"
        );
        Validate.notNull(setActiveConversationId, "setActiveConversationId must not be null");
        Validate.notNull(refreshSidebar, "refreshSidebar must not be null");
        Validate.notNull(selectConversation, "selectConversation must not be null");

        if (!saveResult.saved()) {
            return false;
        }

        UUID conversationId = saveResult.conversationId();
        setCurrentConversationId.accept(conversationId);
        setPendingUnsavedConversationRenderMode.accept(saveResult.pendingUnsavedConversationRenderMode());
        if (saveResult.createdConversation()) {
            setActiveConversationId.accept(conversationId);
        }

        refreshSidebar.run();
        if (selectCreatedConversation && saveResult.createdConversation()) {
            selectConversation.accept(conversationId);
        }
        return true;
    }
}
