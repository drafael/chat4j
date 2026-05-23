package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.RenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderModeChangeDispatchCoordinatorTest {

    @Test
    @DisplayName("Apply delegates change and UI sync")
    void apply_whenModeProvided_delegatesChangeAndUiSync() {
        var capturedMode = new AtomicReference<RenderMode>();
        var synced = new AtomicBoolean(false);
        var subject = new RenderModeChangeDispatchCoordinator(
                mode -> {
                    capturedMode.set(mode);
                    return new RenderModeChangeCoordinator.ApplyResult(true);
                },
                (applyResult, syncTogglePreviewMenuSelection) -> {
                    syncTogglePreviewMenuSelection.run();
                    return applyResult.handled();
                }
        );

        boolean handled = subject.apply(RenderMode.MARKDOWN, () -> synced.set(true));

        assertThat(handled).isTrue();
        assertThat(capturedMode.get()).isEqualTo(RenderMode.MARKDOWN);
        assertThat(synced).isTrue();
    }

    @Test
    @DisplayName("Apply validates required arguments")
    void apply_whenRequiredArgumentMissing_throwsException() {
        var subject = new RenderModeChangeDispatchCoordinator(
                mode -> new RenderModeChangeCoordinator.ApplyResult(true),
                (applyResult, syncTogglePreviewMenuSelection) -> true
        );

        assertThatThrownBy(() -> subject.apply(null, () -> {}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("mode");
    }
}
