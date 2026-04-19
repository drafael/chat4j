package com.github.drafael.chat4j.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HelpMenuVisibilityResolverTest {

    private final HelpMenuVisibilityResolver subject = new HelpMenuVisibilityResolver();

    @Test
    @DisplayName("Should show help menu on non-macOS regardless of screen menu bar setting")
    void shouldShowHelpMenu_whenNonMac_returnsTrue() {
        assertThat(subject.shouldShowHelpMenu(false, false)).isTrue();
        assertThat(subject.shouldShowHelpMenu(false, true)).isTrue();
    }

    @Test
    @DisplayName("Should hide help menu on macOS when screen menu bar is enabled")
    void shouldShowHelpMenu_whenMacAndScreenMenuBarEnabled_returnsFalse() {
        assertThat(subject.shouldShowHelpMenu(true, true)).isFalse();
    }

    @Test
    @DisplayName("Should show help menu on macOS when screen menu bar is disabled")
    void shouldShowHelpMenu_whenMacAndScreenMenuBarDisabled_returnsTrue() {
        assertThat(subject.shouldShowHelpMenu(true, false)).isTrue();
    }
}
