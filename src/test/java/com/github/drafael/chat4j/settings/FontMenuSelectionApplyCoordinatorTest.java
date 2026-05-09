package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FontMenuSelectionApplyCoordinatorTest {

    @Test
    @DisplayName("Apply updates all tracked font-selection state fields and returns applied state")
    void apply_whenCalled_updatesStateAndReturnsSelectionState() {
        var subject = new FontMenuSelectionApplyCoordinator();
        var state = new FontMenuSelectionSynchronizer.FontMenuSelectionState("Inter", 14, "JetBrains Mono");

        var appFamily = new AtomicReference<String>();
        var appSize = new AtomicReference<Integer>();
        var codeFamily = new AtomicReference<String>();

        FontMenuSelectionSynchronizer.FontMenuSelectionState applied = subject.apply(
                state,
                appFamily::set,
                appSize::set,
                codeFamily::set
        );

        assertThat(applied).isSameAs(state);
        assertThat(appFamily.get()).isEqualTo("Inter");
        assertThat(appSize.get()).isEqualTo(14);
        assertThat(codeFamily.get()).isEqualTo("JetBrains Mono");
    }

    @Test
    @DisplayName("Apply validates required arguments")
    void apply_whenInvalidInput_throwsException() {
        var subject = new FontMenuSelectionApplyCoordinator();
        var state = new FontMenuSelectionSynchronizer.FontMenuSelectionState("Inter", 14, "JetBrains Mono");

        assertThatThrownBy(() -> subject.apply(null, value -> {
        }, value -> {
        }, value -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("selectionState");

        assertThatThrownBy(() -> subject.apply(state, null, value -> {
        }, value -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("setLastMenuSelectedAppFontFamily");
    }
}
