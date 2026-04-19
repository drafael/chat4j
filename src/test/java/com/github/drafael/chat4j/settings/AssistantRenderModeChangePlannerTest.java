package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantRenderModeChangePlannerTest {

    private final AssistantRenderModeChangePlanner subject = new AssistantRenderModeChangePlanner();

    @Test
    @DisplayName("Plan ignores changes when mode is null")
    void plan_whenModeIsNull_returnsIgnorePlan() {
        var plan = subject.plan(UUID.randomUUID(), null);

        assertThat(plan.ignore()).isTrue();
        assertThat(plan.conversationIdToPersist()).isNull();
        assertThat(plan.modeToPersist()).isNull();
        assertThat(plan.pendingUnsavedMode()).isNull();
    }

    @Test
    @DisplayName("Plan persists mode when conversation is active")
    void plan_whenConversationIsActive_returnsPersistencePlan() {
        UUID conversationId = UUID.fromString("e1d510c3-4a8c-40e4-b8fc-123fc1798427");

        var plan = subject.plan(conversationId, AssistantRenderMode.MARKDOWN);

        assertThat(plan.ignore()).isFalse();
        assertThat(plan.conversationIdToPersist()).isEqualTo(conversationId);
        assertThat(plan.modeToPersist()).isEqualTo(AssistantRenderMode.MARKDOWN);
        assertThat(plan.pendingUnsavedMode()).isNull();
    }

    @Test
    @DisplayName("Plan stores pending mode when no conversation is active")
    void plan_whenNoConversationIsActive_returnsPendingPlan() {
        var plan = subject.plan(null, AssistantRenderMode.PREVIEW);

        assertThat(plan.ignore()).isFalse();
        assertThat(plan.conversationIdToPersist()).isNull();
        assertThat(plan.modeToPersist()).isNull();
        assertThat(plan.pendingUnsavedMode()).isEqualTo(AssistantRenderMode.PREVIEW);
    }
}
