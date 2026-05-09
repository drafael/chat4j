package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.support.ModelSelectionCodec;
import lombok.NonNull;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static java.util.Collections.emptyList;

public class ConversationLoadResultPlanner {

    private final RequestFreshnessChecker requestFreshnessChecker;
    private final ConversationModeResolver conversationModeResolver;

    public ConversationLoadResultPlanner(
            @NonNull RequestFreshnessChecker requestFreshnessChecker,
            @NonNull ConversationModeResolver conversationModeResolver
    ) {
        this.requestFreshnessChecker = requestFreshnessChecker;
        this.conversationModeResolver = conversationModeResolver;
    }

    public LoadedConversationPlan planLoaded(
            long requestId,
            UUID activeConversationId,
            @NonNull UUID loadedConversationId,
            @NonNull List<ConversationRepo.MessageRecord> records,
            ConversationRepo.ConversationRecord conversation
    ) {

        if (!shouldApply(requestId, activeConversationId, loadedConversationId)) {
            return LoadedConversationPlan.ignorePlan();
        }

        List<Message> messages = records.stream()
                .map(ConversationRepo.MessageRecord::message)
                .toList();

        String selectedModelKey = conversation == null
                ? null
                : ModelSelectionCodec.format(conversation.provider(), conversation.model());

        AssistantRenderMode modeToApply = conversationModeResolver.resolve(loadedConversationId);
        return LoadedConversationPlan.applyPlan(
                loadedConversationId,
                messages,
                records.size(),
                modeToApply,
                selectedModelKey
        );
    }

    public boolean shouldHandleFailure(long requestId, UUID activeConversationId, @NonNull UUID failedConversationId) {
        return shouldApply(requestId, activeConversationId, failedConversationId);
    }

    private boolean shouldApply(long requestId, UUID activeConversationId, UUID loadedConversationId) {
        return requestFreshnessChecker.isCurrentRequest(requestId)
                && Objects.equals(activeConversationId, loadedConversationId);
    }

    public record LoadedConversationPlan(
            boolean ignore,
            UUID conversationId,
            List<Message> messages,
            int persistedCount,
            AssistantRenderMode assistantRenderMode,
            String selectedModelKey
    ) {

        static LoadedConversationPlan ignorePlan() {
            return new LoadedConversationPlan(true, null, emptyList(), 0, null, null);
        }

        static LoadedConversationPlan applyPlan(
                UUID conversationId,
                List<Message> messages,
                int persistedCount,
                AssistantRenderMode assistantRenderMode,
                String selectedModelKey
        ) {

            return new LoadedConversationPlan(
                    false,
                    conversationId,
                    messages,
                    persistedCount,
                    assistantRenderMode,
                    selectedModelKey
            );
        }
    }

    @FunctionalInterface
    public interface RequestFreshnessChecker {
        boolean isCurrentRequest(long requestId);
    }

    @FunctionalInterface
    public interface ConversationModeResolver {
        AssistantRenderMode resolve(UUID conversationId);
    }
}
