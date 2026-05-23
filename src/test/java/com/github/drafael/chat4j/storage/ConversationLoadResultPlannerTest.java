package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.support.ModelSelectionCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationLoadResultPlannerTest {

    @Test
    @DisplayName("Plan loaded ignores stale requests")
    void planLoaded_whenRequestStale_returnsIgnorePlan() {
        var subject = new ConversationLoadResultPlanner(requestId -> false);
        UUID conversationId = UUID.randomUUID();

        var plan = subject.planLoaded(1L, conversationId, conversationId, List.of(), null);

        assertThat(plan.ignore()).isTrue();
    }

    @Test
    @DisplayName("Plan loaded includes messages and selected model without render mode")
    void planLoaded_whenCurrentRequest_returnsApplyPlan() {
        var subject = new ConversationLoadResultPlanner(requestId -> requestId == 7L);
        UUID conversationId = UUID.randomUUID();
        var message = Message.user("hello");

        var plan = subject.planLoaded(
                7L,
                conversationId,
                conversationId,
                List.of(new ConversationRepo.MessageRecord(UUID.randomUUID(), message, LocalDateTime.now())),
                new ConversationRepo.ConversationRecord(
                        conversationId,
                        "demo",
                        "OpenAI",
                        "gpt-4o",
                        false,
                        "off",
                        false,
                        null,
                        LocalDateTime.now(),
                        LocalDateTime.now()
                )
        );

        assertThat(plan.ignore()).isFalse();
        assertThat(plan.messages()).containsExactly(message);
        assertThat(plan.persistedCount()).isEqualTo(1);
        assertThat(plan.selectedModelKey()).isEqualTo(ModelSelectionCodec.format("OpenAI", "gpt-4o"));
    }
}
