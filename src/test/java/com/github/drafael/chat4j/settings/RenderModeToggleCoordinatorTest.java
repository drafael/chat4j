package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.RenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderModeToggleCoordinatorTest {

    @Test
    @DisplayName("Apply returns false and skips render mode apply when preview menu selection is syncing")
    void apply_whenSyncingSelection_returnsFalseAndSkipsApply() {
        var subject = new RenderModeToggleCoordinator();
        var applyCalls = new AtomicInteger();

        boolean handled = subject.apply(true, true, (mode, userInitiated) -> applyCalls.incrementAndGet());

        assertThat(handled).isFalse();
        assertThat(applyCalls.get()).isZero();
    }

    @Test
    @DisplayName("Apply maps selected preview toggle to preview render mode")
    void apply_whenPreviewSelected_appliesPreviewMode() {
        var subject = new RenderModeToggleCoordinator();
        var appliedMode = new AtomicReference<RenderMode>();
        var userInitiated = new AtomicReference<Boolean>();

        boolean handled = subject.apply(true, false, (mode, applyUserInitiated) -> {
            appliedMode.set(mode);
            userInitiated.set(applyUserInitiated);
        });

        assertThat(handled).isTrue();
        assertThat(appliedMode.get()).isEqualTo(RenderMode.PREVIEW);
        assertThat(userInitiated.get()).isTrue();
    }

    @Test
    @DisplayName("Apply maps unselected preview toggle to markdown render mode")
    void apply_whenPreviewUnselected_appliesMarkdownMode() {
        var subject = new RenderModeToggleCoordinator();
        var appliedMode = new AtomicReference<RenderMode>();

        boolean handled = subject.apply(false, false, (mode, userInitiated) -> appliedMode.set(mode));

        assertThat(handled).isTrue();
        assertThat(appliedMode.get()).isEqualTo(RenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Apply validates render mode applier")
    void apply_whenApplierMissing_throwsException() {
        var subject = new RenderModeToggleCoordinator();

        assertThatThrownBy(() -> subject.apply(true, false, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("renderModeApplier");
    }
}
