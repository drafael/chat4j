package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.provider.api.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationLoadResultPlannerTest {

    @Test
    @DisplayName("PlanLoaded returns ignore plan for stale request and does not resolve mode")
    void planLoaded_whenRequestStale_returnsIgnoreAndSkipsModeResolver() {
        AtomicInteger modeResolverCalls = new AtomicInteger();
        var subject = new ConversationLoadResultPlanner(
                requestId -> false,
                conversationId -> {
                    modeResolverCalls.incrementAndGet();
                    return AssistantRenderMode.PREVIEW;
                }
        );

        UUID conversationId = UUID.randomUUID();
        var plan = subject.planLoaded(
                1L,
                conversationId,
                conversationId,
                List.of(messageRecord(Message.user("hello"))),
                Optional.empty()
        );

        assertThat(plan.ignore()).isTrue();
        assertThat(plan.messages()).isEmpty();
        assertThat(plan.selectedModelKey()).isNull();
        assertThat(modeResolverCalls.get()).isZero();
    }

    @Test
    @DisplayName("PlanLoaded builds apply plan for current matching request")
    void planLoaded_whenRequestCurrentAndMatching_buildsApplyPlan() {
        var subject = new ConversationLoadResultPlanner(
                requestId -> requestId == 7L,
                conversationId -> AssistantRenderMode.MARKDOWN
        );

        UUID conversationId = UUID.randomUUID();
        List<ConversationRepo.MessageRecord> records = List.of(
                messageRecord(Message.user("u1")),
                messageRecord(Message.assistant("a1"))
        );
        Optional<ConversationRepo.ConversationRecord> conversation = Optional.of(
                new ConversationRepo.ConversationRecord(
                        conversationId,
                        "Title",
                        "OpenAI",
                        "gpt-4.1",
                        false,
                        "off",
                        false,
                        null,
                        LocalDateTime.now(),
                        LocalDateTime.now()
                )
        );

        var plan = subject.planLoaded(7L, conversationId, conversationId, records, conversation);

        assertThat(plan.ignore()).isFalse();
        assertThat(plan.conversationId()).isEqualTo(conversationId);
        assertThat(plan.messages()).extracting(Message::content).containsExactly("u1", "a1");
        assertThat(plan.persistedCount()).isEqualTo(2);
        assertThat(plan.assistantRenderMode()).isEqualTo(AssistantRenderMode.MARKDOWN);
        assertThat(plan.selectedModelKey()).isEqualTo("OpenAI > gpt-4.1");
    }

    @Test
    @DisplayName("ShouldHandleFailure only returns true for current matching request")
    void shouldHandleFailure_whenRequestOrConversationMismatch_returnsExpectedFlag() {
        var subject = new ConversationLoadResultPlanner(
                requestId -> requestId == 5L,
                conversationId -> AssistantRenderMode.PREVIEW
        );

        UUID activeConversationId = UUID.randomUUID();

        assertThat(subject.shouldHandleFailure(5L, activeConversationId, activeConversationId)).isTrue();
        assertThat(subject.shouldHandleFailure(4L, activeConversationId, activeConversationId)).isFalse();
        assertThat(subject.shouldHandleFailure(5L, activeConversationId, UUID.randomUUID())).isFalse();
    }

    private ConversationRepo.MessageRecord messageRecord(Message message) {
        return new ConversationRepo.MessageRecord(UUID.randomUUID(), message, LocalDateTime.now());
    }
}
