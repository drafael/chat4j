package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThemeMenuSelectionApplyCoordinatorTest {

    @Test
    @DisplayName("Apply updates last selected theme and returns applied value")
    void apply_whenCalled_updatesStateAndReturnsValue() {
        var subject = new ThemeMenuSelectionApplyCoordinator();
        var lastSelectedTheme = new AtomicReference<String>();

        String applied = subject.apply("Dracula", lastSelectedTheme::set);

        assertThat(applied).isEqualTo("Dracula");
        assertThat(lastSelectedTheme.get()).isEqualTo("Dracula");
    }

    @Test
    @DisplayName("Apply validates required setter")
    void apply_whenSetterMissing_throwsException() {
        var subject = new ThemeMenuSelectionApplyCoordinator();

        assertThatThrownBy(() -> subject.apply("GitHub", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("setLastMenuSelectedTheme must not be null");
    }
}
