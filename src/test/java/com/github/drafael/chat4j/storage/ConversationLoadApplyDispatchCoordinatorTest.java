package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.provider.api.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static java.util.Collections.emptyList;

class ConversationLoadApplyDispatchCoordinatorTest {

    @Test
    @DisplayName("ApplyLoaded plans and delegates to applier with computed plan")
    void applyLoaded_whenCalled_plansAndDelegatesToApplier() {
        UUID conversationId = UUID.randomUUID();
        UUID currentConversationId = UUID.randomUUID();
        List<ConversationRepo.MessageRecord> records = List.of(
                new ConversationRepo.MessageRecord(UUID.randomUUID(), Message.user("hello"), LocalDateTime.now())
        );
        ConversationRepo.ConversationRecord conversation = null;

        var plannerInputs = new AtomicReference<String>();
        var appliedPlan = new AtomicReference<ConversationLoadResultPlanner.LoadedConversationPlan>();

        var subject = new ConversationLoadApplyDispatchCoordinator(
                (requestId, activeConversationId, loadedConversationId, loadedRecords, loadedConversation) -> {
                    plannerInputs.set(requestId + ":" + activeConversationId + ":" + loadedConversationId);
                    return ConversationLoadResultPlanner.LoadedConversationPlan.applyPlan(
                            loadedConversationId,
                            List.of(Message.assistant("ok")),
                            3,
                            AssistantRenderMode.PREVIEW,
                            "OpenAI > gpt-4.1"
                    );
                },
                (plan, historyLoader, persistedCountMarker, renderModeApplier, selectedModelSetter, conversationSelector) -> {
                    appliedPlan.set(plan);
                    return true;
                }
        );

        boolean applied = subject.applyLoaded(
                55L,
                currentConversationId,
                conversationId,
                records,
                conversation,
                history -> {
                },
                (id, count) -> {
                },
                (mode, userInitiated) -> {
                },
                model -> {
                },
                id -> {
                }
        );

        assertThat(applied).isTrue();
        assertThat(plannerInputs.get()).isEqualTo("55:" + currentConversationId + ":" + conversationId);
        assertThat(appliedPlan.get()).isNotNull();
        assertThat(appliedPlan.get().conversationId()).isEqualTo(conversationId);
        assertThat(appliedPlan.get().persistedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("ApplyLoaded validates required arguments")
    void applyLoaded_whenArgumentMissing_throwsException() {
        var subject = new ConversationLoadApplyDispatchCoordinator(
                (requestId, activeConversationId, loadedConversationId, records, conversation) ->
                        ConversationLoadResultPlanner.LoadedConversationPlan.ignorePlan(),
                (plan, historyLoader, persistedCountMarker, renderModeApplier, selectedModelSetter, conversationSelector) ->
                        false
        );

        assertThatThrownBy(() -> subject.applyLoaded(
                1L,
                UUID.randomUUID(),
                null,
                emptyList(),
                null,
                history -> {
                },
                (id, count) -> {
                },
                (mode, userInitiated) -> {
                },
                model -> {
                },
                id -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("conversationId must not be null");
    }

    @Test
    @DisplayName("Constructor validates planner/applier")
    void constructor_whenDependencyMissing_throwsException() {
        assertThatThrownBy(() -> new ConversationLoadApplyDispatchCoordinator(
                (ConversationLoadApplyDispatchCoordinator.Planner) null,
                (plan, historyLoader, persistedCountMarker, renderModeApplier, selectedModelSetter, conversationSelector) ->
                        true
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("planner must not be null");

        assertThatThrownBy(() -> new ConversationLoadApplyDispatchCoordinator(
                (requestId, activeConversationId, loadedConversationId, records, conversation) ->
                        ConversationLoadResultPlanner.LoadedConversationPlan.ignorePlan(),
                (ConversationLoadApplyDispatchCoordinator.Applier) null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("applier must not be null");
    }
}
