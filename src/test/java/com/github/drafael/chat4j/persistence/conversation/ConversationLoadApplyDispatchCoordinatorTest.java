package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.provider.api.Message;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationLoadApplyDispatchCoordinatorTest {

    @Test
    @DisplayName("Apply loaded delegates planner result to applier")
    void applyLoaded_whenCalled_delegatesPlanToApplier() {
        UUID conversationId = UUID.randomUUID();
        var applierCalled = new AtomicBoolean(false);
        var subject = new ConversationLoadApplyDispatchCoordinator(
                (requestId, activeConversationId, loadedConversationId, records, conversation) ->
                        ConversationLoadResultPlanner.LoadedConversationPlan.applyPlan(conversationId, List.of(Message.user("hello")), 1, "OpenAI:gpt-4o"),
                (plan, historyLoader, persistedCountMarker, selectedModelSetter, conversationSelector) -> {
                    applierCalled.set(true);
                    return !plan.ignore();
                }
        );

        boolean applied = subject.applyLoaded(
                1L,
                conversationId,
                conversationId,
                List.of(),
                null,
                messages -> {},
                (id, count) -> {},
                model -> {},
                id -> {}
        );

        assertThat(applied).isTrue();
        assertThat(applierCalled).isTrue();
    }

    @Test
    @DisplayName("Apply loaded validates required arguments")
    void applyLoaded_whenConversationIdMissing_throwsException() {
        var subject = new ConversationLoadApplyDispatchCoordinator(
                (requestId, activeConversationId, loadedConversationId, records, conversation) -> ConversationLoadResultPlanner.LoadedConversationPlan.ignorePlan(),
                (plan, historyLoader, persistedCountMarker, selectedModelSetter, conversationSelector) -> false
        );

        assertThatThrownBy(() -> subject.applyLoaded(1L, null, null, List.of(), null, messages -> {}, (id, count) -> {}, model -> {}, id -> {}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("conversationId");
    }
}
