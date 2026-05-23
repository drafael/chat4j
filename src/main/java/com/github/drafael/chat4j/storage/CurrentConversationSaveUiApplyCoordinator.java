package com.github.drafael.chat4j.storage;

import lombok.NonNull;

import java.util.UUID;
import java.util.function.Consumer;

public class CurrentConversationSaveUiApplyCoordinator {

    public boolean apply(
            @NonNull CurrentConversationSaveCoordinator.SaveResult saveResult,
            @NonNull Consumer<UUID> setCurrentConversationId,
            @NonNull Consumer<UUID> setActiveConversationId,
            @NonNull Runnable refreshSidebar,
            @NonNull Consumer<UUID> selectConversation,
            boolean selectCreatedConversation
    ) {

        if (!saveResult.saved()) {
            return false;
        }

        UUID conversationId = saveResult.conversationId();
        setCurrentConversationId.accept(conversationId);
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
