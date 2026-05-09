package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantRenderModeChangeUiApplyCoordinatorTest {

    @Test
    @DisplayName("Apply syncs toggle and updates pending mode when change result is handled")
    void apply_whenHandled_syncsToggleAndUpdatesPendingMode() {
        var subject = new AssistantRenderModeChangeUiApplyCoordinator();
        var calls = new ArrayList<String>();
        var pendingMode = new AtomicReference<AssistantRenderMode>();

        boolean handled = subject.apply(
                new AssistantRenderModeChangeCoordinator.ApplyResult(true, AssistantRenderMode.PREVIEW),
                () -> calls.add("sync-toggle"),
                mode -> {
                    calls.add("set-pending");
                    pendingMode.set(mode);
                }
        );

        assertThat(handled).isTrue();
        assertThat(calls).containsExactly("sync-toggle", "set-pending");
        assertThat(pendingMode.get()).isEqualTo(AssistantRenderMode.PREVIEW);
    }

    @Test
    @DisplayName("Apply skips UI updates when change result is not handled")
    void apply_whenIgnored_skipsUiUpdates() {
        var subject = new AssistantRenderModeChangeUiApplyCoordinator();
        var calls = new ArrayList<String>();

        boolean handled = subject.apply(
                new AssistantRenderModeChangeCoordinator.ApplyResult(false, AssistantRenderMode.MARKDOWN),
                () -> calls.add("sync-toggle"),
                mode -> calls.add("set-pending")
        );

        assertThat(handled).isFalse();
        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("Apply validates required arguments")
    void apply_whenArgumentMissing_throwsException() {
        var subject = new AssistantRenderModeChangeUiApplyCoordinator();

        assertThatThrownBy(() -> subject.apply(
                null,
                () -> {
                },
                mode -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("applyResult");

        assertThatThrownBy(() -> subject.apply(
                new AssistantRenderModeChangeCoordinator.ApplyResult(true, AssistantRenderMode.PREVIEW),
                null,
                mode -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("syncTogglePreviewMenuSelection");
    }
}
