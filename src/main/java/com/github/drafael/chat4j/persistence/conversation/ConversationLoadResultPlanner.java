package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.provider.support.ModelSelectionCodec;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.NonNull;

import static java.util.Collections.emptyList;

public class ConversationLoadResultPlanner {

    private final RequestFreshnessChecker requestFreshnessChecker;

    public ConversationLoadResultPlanner(@NonNull RequestFreshnessChecker requestFreshnessChecker) {
        this.requestFreshnessChecker = requestFreshnessChecker;
    }

    public LoadedConversationPlan planLoaded(
            long requestId,
            UUID activeConversationId,
            @NonNull UUID loadedConversationId,
            @NonNull List<ConversationRepository.MessageRecord> records,
            ConversationRepository.ConversationRecord conversation
    ) {

        if (!shouldApply(requestId, activeConversationId, loadedConversationId)) {
            return LoadedConversationPlan.ignorePlan();
        }

        String selectedModelKey = conversation == null
                ? null
                : ModelSelectionCodec.format(conversation.provider(), conversation.model());

        return LoadedConversationPlan.applyPlan(loadedConversationId, records, selectedModelKey);
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
            List<ConversationRepository.MessageRecord> records,
            String selectedModelKey
    ) {
        public LoadedConversationPlan {
            records = List.copyOf(records);
        }

        static LoadedConversationPlan ignorePlan() {
            return new LoadedConversationPlan(true, null, emptyList(), null);
        }

        static LoadedConversationPlan applyPlan(
                UUID conversationId,
                List<ConversationRepository.MessageRecord> records,
                String selectedModelKey
        ) {
            return new LoadedConversationPlan(false, conversationId, records, selectedModelKey);
        }
    }

    @FunctionalInterface
    public interface RequestFreshnessChecker {
        boolean isCurrentRequest(long requestId);
    }
}
