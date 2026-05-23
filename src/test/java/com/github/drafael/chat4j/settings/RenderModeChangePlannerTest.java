package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.RenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RenderModeChangePlannerTest {

    private final RenderModeChangePlanner subject = new RenderModeChangePlanner();

    @Test
    @DisplayName("Plan ignores null mode")
    void plan_whenModeMissing_ignoresChange() {
        RenderModeChangePlanner.ChangePlan plan = subject.plan(null);

        assertThat(plan.ignore()).isTrue();
        assertThat(plan.modeToPersist()).isNull();
    }

    @Test
    @DisplayName("Plan persists global mode")
    void plan_whenModeProvided_persistsGlobalMode() {
        RenderModeChangePlanner.ChangePlan plan = subject.plan(RenderMode.MARKDOWN);

        assertThat(plan.ignore()).isFalse();
        assertThat(plan.modeToPersist()).isEqualTo(RenderMode.MARKDOWN);
    }
}
