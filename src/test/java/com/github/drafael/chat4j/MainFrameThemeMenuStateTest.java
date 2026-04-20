package com.github.drafael.chat4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MainFrameThemeMenuStateTest {

    @Test
    @DisplayName("Default constructor starts with unbuilt menu and no selected theme")
    void constructor_whenDefault_startsWithUnbuiltMenuAndNoSelection() {
        var subject = new MainFrameThemeMenuState();

        assertThat(subject.themesMenuBuilt()).isFalse();
        assertThat(subject.lastMenuSelectedTheme()).isNull();
    }

    @Test
    @DisplayName("Setters update tracked theme-menu state")
    void stateMutators_whenCalled_updateTrackedState() {
        var subject = new MainFrameThemeMenuState(false, null);

        subject.setThemesMenuBuilt(true);
        subject.setLastMenuSelectedTheme("Catppuccin");

        assertThat(subject.themesMenuBuilt()).isTrue();
        assertThat(subject.lastMenuSelectedTheme()).isEqualTo("Catppuccin");
    }
}
