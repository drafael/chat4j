package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.support.ModelSelectionCodec;
import org.apache.commons.lang3.Validate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class ConversationLoadResultPlanner {

    private final RequestFreshnessChecker requestFreshnessChecker;
    private final ConversationModeResolver conversationModeResolver;

    public ConversationLoadResultPlanner(
            RequestFreshnessChecker requestFreshnessChecker,
            ConversationModeResolver conversationModeResolver
    ) {
        this.requestFreshnessChecker = Validate.notNull(
                requestFreshnessChecker,
                "requestFreshnessChecker must not be null"
        );
        this.conversationModeResolver = Validate.notNull(
                conversationModeResolver,
                "conversationModeResolver must not be null"
        );
    }

    public LoadedConversationPlan planLoaded(
            long requestId,
            UUID activeConversationId,
            UUID loadedConversationId,
            List<ConversationRepo.MessageRecord> records,
            Optional<ConversationRepo.ConversationRecord> conversation
    ) {
        Validate.notNull(loadedConversationId, "loadedConversationId must not be null");
        Validate.notNull(records, "records must not be null");
        Validate.notNull(conversation, "conversation must not be null");

        if (!shouldApply(requestId, activeConversationId, loadedConversationId)) {
            return LoadedConversationPlan.ignorePlan();
        }

        List<Message> messages = records.stream()
                .map(ConversationRepo.MessageRecord::message)
                .toList();

        String selectedModelKey = conversation
                .map(record -> ModelSelectionCodec.format(record.provider(), record.model()))
                .orElse(null);

        AssistantRenderMode modeToApply = conversationModeResolver.resolve(loadedConversationId);
        return LoadedConversationPlan.applyPlan(
                loadedConversationId,
                messages,
                records.size(),
                modeToApply,
                selectedModelKey
        );
    }

    public boolean shouldHandleFailure(long requestId, UUID activeConversationId, UUID failedConversationId) {
        Validate.notNull(failedConversationId, "failedConversationId must not be null");
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
            return new LoadedConversationPlan(true, null, List.of(), 0, null, null);
        }

        static LoadedConversationPlan applyPlan(
                UUID conversationId,
                List<Message> messages,
                int persistedCount,
                AssistantRenderMode assistantRenderMode,
                String selectedModelKey
        ) {
            Validate.notNull(conversationId, "conversationId must not be null");
            Validate.notNull(messages, "messages must not be null");
            Validate.notNull(assistantRenderMode, "assistantRenderMode must not be null");

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
