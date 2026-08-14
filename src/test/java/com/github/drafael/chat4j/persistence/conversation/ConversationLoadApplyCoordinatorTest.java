package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.provider.api.Message;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
        List<ConversationRepository.MessageRecord> records = List.of(new ConversationRepository.MessageRecord(
                UUID.randomUUID(),
                1,
                Message.user("hello")
        ));
        var loadedRecords = new AtomicReference<List<ConversationRepository.MessageRecord>>();
        var selectedModel = new AtomicReference<String>();
        var selectedConversation = new AtomicReference<UUID>();
        var applicationOrder = new ArrayList<String>();

        boolean applied = subject.apply(
                ConversationLoadResultPlanner.LoadedConversationPlan.applyPlan(conversationId, records, "OpenAI:gpt-4o"),
                loaded -> {
                    applicationOrder.add("history");
                    loadedRecords.set(loaded);
                },
                model -> {
                    applicationOrder.add("model");
                    selectedModel.set(model);
                },
                selected -> {
                    applicationOrder.add("conversation");
                    selectedConversation.set(selected);
                }
        );

        assertThat(applied).isTrue();
        assertThat(loadedRecords.get()).isEqualTo(records);
        assertThat(selectedModel.get()).isEqualTo("OpenAI:gpt-4o");
        assertThat(selectedConversation.get()).isEqualTo(conversationId);
        assertThat(applicationOrder).containsExactly("conversation", "model", "history");
    }

    @Test
    @DisplayName("Apply ignores ignored plans")
    void apply_whenPlanIgnored_returnsFalse() {
        boolean applied = subject.apply(
                ConversationLoadResultPlanner.LoadedConversationPlan.ignorePlan(),
                messages -> {},
                selectedModel -> {},
                selectedConversation -> {}
        );

        assertThat(applied).isFalse();
    }

    @Test
    @DisplayName("Apply validates required arguments")
    void apply_whenPlanMissing_throwsException() {
        assertThatThrownBy(() -> subject.apply(null, messages -> {}, model -> {}, id -> {}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("plan");
    }
}
