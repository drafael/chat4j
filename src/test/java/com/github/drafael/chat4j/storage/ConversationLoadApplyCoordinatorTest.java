package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.provider.api.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static java.util.Collections.emptyList;

class ConversationLoadApplyCoordinatorTest {

    @Test
    @DisplayName("Apply returns false and skips callbacks when plan is ignored")
    void apply_whenPlanIgnored_returnsFalseAndSkipsCallbacks() {
        var subject = new ConversationLoadApplyCoordinator();
        var callbackCalls = new AtomicInteger();

        boolean applied = subject.apply(
                ConversationLoadResultPlanner.LoadedConversationPlan.ignorePlan(),
                messages -> callbackCalls.incrementAndGet(),
                (conversationId, persistedCount) -> callbackCalls.incrementAndGet(),
                (mode, userInitiated) -> callbackCalls.incrementAndGet(),
                modelKey -> callbackCalls.incrementAndGet(),
                conversationId -> callbackCalls.incrementAndGet()
        );

        assertThat(applied).isFalse();
        assertThat(callbackCalls.get()).isZero();
    }

    @Test
    @DisplayName("Apply applies loaded conversation state and returns true")
    void apply_whenPlanNeedsApply_appliesLoadedConversationStateAndReturnsTrue() {
        var subject = new ConversationLoadApplyCoordinator();
        var calls = new ArrayList<String>();
        var loadedMessages = List.of(Message.user("hello"), Message.assistant("world"));
        UUID conversationId = UUID.randomUUID();

        var plan = ConversationLoadResultPlanner.LoadedConversationPlan.applyPlan(
                conversationId,
                loadedMessages,
                2,
                AssistantRenderMode.PREVIEW,
                "OpenAI > gpt-4.1"
        );

        AtomicReference<List<Message>> appliedMessages = new AtomicReference<>();
        AtomicReference<String> selectedModel = new AtomicReference<>();
        AtomicReference<UUID> selectedConversation = new AtomicReference<>();

        boolean applied = subject.apply(
                plan,
                messages -> {
                    calls.add("history");
                    appliedMessages.set(messages);
                },
                (id, persistedCount) -> {
                    calls.add("persisted:%d".formatted(persistedCount));
                    assertThat(id).isEqualTo(conversationId);
                },
                (mode, userInitiated) -> {
                    calls.add("mode:%s:%s".formatted(mode, userInitiated));
                    assertThat(mode).isEqualTo(AssistantRenderMode.PREVIEW);
                    assertThat(userInitiated).isTrue();
                },
                modelKey -> {
                    calls.add("model");
                    selectedModel.set(modelKey);
                },
                id -> {
                    calls.add("select");
                    selectedConversation.set(id);
                }
        );

        assertThat(applied).isTrue();
        assertThat(appliedMessages.get()).isEqualTo(loadedMessages);
        assertThat(selectedModel.get()).isEqualTo("OpenAI > gpt-4.1");
        assertThat(selectedConversation.get()).isEqualTo(conversationId);
        assertThat(calls).containsExactly("history", "persisted:2", "mode:PREVIEW:true", "model", "select");
    }

    @Test
    @DisplayName("Apply skips selected-model callback when plan has no selected model")
    void apply_whenSelectedModelMissing_skipsSelectedModelCallback() {
        var subject = new ConversationLoadApplyCoordinator();
        var selectedModelCalls = new AtomicInteger();

        var plan = ConversationLoadResultPlanner.LoadedConversationPlan.applyPlan(
                UUID.randomUUID(),
                emptyList(),
                0,
                AssistantRenderMode.MARKDOWN,
                null
        );

        boolean applied = subject.apply(
                plan,
                messages -> {
                },
                (conversationId, persistedCount) -> {
                },
                (mode, userInitiated) -> {
                },
                modelKey -> selectedModelCalls.incrementAndGet(),
                conversationId -> {
                }
        );

        assertThat(applied).isTrue();
        assertThat(selectedModelCalls.get()).isZero();
    }

    @Test
    @DisplayName("Apply validates required arguments")
    void apply_whenArgumentMissing_throwsException() {
        var subject = new ConversationLoadApplyCoordinator();

        assertThatThrownBy(() -> subject.apply(
                null,
                messages -> {
                },
                (conversationId, persistedCount) -> {
                },
                (mode, userInitiated) -> {
                },
                modelKey -> {
                },
                conversationId -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("plan must not be null");
    }
}
