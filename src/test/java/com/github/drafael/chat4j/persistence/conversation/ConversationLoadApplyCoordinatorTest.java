package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.provider.api.Message;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationLoadApplyCoordinatorTest {

    private final ConversationLoadApplyCoordinator subject = new ConversationLoadApplyCoordinator();

    @Test
    @DisplayName("Apply loads history and selection without applying render mode")
    void apply_whenPlanIsActive_appliesConversationData() {
        UUID conversationId = UUID.randomUUID();
        List<Message> messages = List.of(Message.user("hello"));
        var loadedMessages = new AtomicReference<List<Message>>();
        var markedCount = new AtomicInteger();
        var selectedModel = new AtomicReference<String>();
        var selectedConversation = new AtomicReference<UUID>();

        boolean applied = subject.apply(
                ConversationLoadResultPlanner.LoadedConversationPlan.applyPlan(conversationId, messages, 1, "OpenAI:gpt-4o"),
                loadedMessages::set,
                (id, count) -> markedCount.set(count),
                selectedModel::set,
                selectedConversation::set
        );

        assertThat(applied).isTrue();
        assertThat(loadedMessages.get()).isEqualTo(messages);
        assertThat(markedCount).hasValue(1);
        assertThat(selectedModel.get()).isEqualTo("OpenAI:gpt-4o");
        assertThat(selectedConversation.get()).isEqualTo(conversationId);
    }

    @Test
    @DisplayName("Apply ignores ignored plans")
    void apply_whenPlanIgnored_returnsFalse() {
        boolean applied = subject.apply(
                ConversationLoadResultPlanner.LoadedConversationPlan.ignorePlan(),
                messages -> {},
                (conversationId, count) -> {},
                selectedModel -> {},
                selectedConversation -> {}
        );

        assertThat(applied).isFalse();
    }

    @Test
    @DisplayName("Apply validates required arguments")
    void apply_whenPlanMissing_throwsException() {
        assertThatThrownBy(() -> subject.apply(null, messages -> {}, (id, count) -> {}, model -> {}, id -> {}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("plan");
    }
}
