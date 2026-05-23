package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.RenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneralSettingsApplyDispatchCoordinatorTest {

    @Test
    @DisplayName("Apply delegates settings result to UI applier")
    void apply_whenCalled_delegatesToUiApplier() {
        var sendApplied = new AtomicBoolean(false);
        var subject = new GeneralSettingsApplyDispatchCoordinator(
                isMacOS -> new GeneralSettingsApplyCoordinator.ApplyResult(true, true, RenderMode.MARKDOWN, RenderMode.MARKDOWN, true),
                (applyResult, sendOnEnterApplier, autoScrollApplier, renderModeApplier, menuBarSettingApplier) -> {
                    sendOnEnterApplier.apply(applyResult.sendOnEnter());
                    return applyResult.defaultRenderMode();
                }
        );

        RenderMode mode = subject.apply(
                true,
                value -> sendApplied.set(value),
                value -> {},
                (value, rerender) -> {},
                value -> {}
        );

        assertThat(mode).isEqualTo(RenderMode.MARKDOWN);
        assertThat(sendApplied).isTrue();
    }

    @Test
    @DisplayName("Apply validates required appliers")
    void apply_whenRequiredApplierMissing_throwsException() {
        var subject = new GeneralSettingsApplyDispatchCoordinator(
                isMacOS -> new GeneralSettingsApplyCoordinator.ApplyResult(true, true, RenderMode.PREVIEW, RenderMode.PREVIEW, true),
                (applyResult, sendOnEnterApplier, autoScrollApplier, renderModeApplier, menuBarSettingApplier) -> applyResult.defaultRenderMode()
        );

        assertThatThrownBy(() -> subject.apply(true, null, value -> {}, (mode, rerender) -> {}, value -> {}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sendOnEnterApplier");
    }
}
