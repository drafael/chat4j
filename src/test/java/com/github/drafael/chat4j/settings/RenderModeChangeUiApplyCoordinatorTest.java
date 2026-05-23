package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderModeChangeUiApplyCoordinatorTest {

    private final RenderModeChangeUiApplyCoordinator subject = new RenderModeChangeUiApplyCoordinator();

    @Test
    @DisplayName("Apply syncs toggle when change was handled")
    void apply_whenHandled_syncsToggle() {
        var synced = new AtomicBoolean(false);

        boolean handled = subject.apply(new RenderModeChangeCoordinator.ApplyResult(true), () -> synced.set(true));

        assertThat(handled).isTrue();
        assertThat(synced).isTrue();
    }

    @Test
    @DisplayName("Apply skips sync when change was ignored")
    void apply_whenIgnored_skipsToggleSync() {
        var synced = new AtomicBoolean(false);

        boolean handled = subject.apply(new RenderModeChangeCoordinator.ApplyResult(false), () -> synced.set(true));

        assertThat(handled).isFalse();
        assertThat(synced).isFalse();
    }

    @Test
    @DisplayName("Apply validates required arguments")
    void apply_whenRequiredArgumentMissing_throwsException() {
        assertThatThrownBy(() -> subject.apply(null, () -> {}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("applyResult");
    }
}
