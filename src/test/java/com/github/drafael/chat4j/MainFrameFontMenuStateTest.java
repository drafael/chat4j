package com.github.drafael.chat4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MainFrameFontMenuStateTest {

    @Test
    @DisplayName("Default constructor starts with unbuilt menu and empty font selections")
    void constructor_whenDefault_startsWithUnbuiltMenuAndEmptySelections() {
        var subject = new MainFrameFontMenuState();

        assertThat(subject.fontMenuBuilt()).isFalse();
        assertThat(subject.lastMenuSelectedAppFontFamily()).isNull();
        assertThat(subject.lastMenuSelectedAppFontSize()).isNull();
        assertThat(subject.lastMenuSelectedCodeFontFamily()).isNull();
    }

    @Test
    @DisplayName("Setters update tracked font-menu state")
    void stateMutators_whenCalled_updateTrackedState() {
        var subject = new MainFrameFontMenuState(false, null, null, null);

        subject.setFontMenuBuilt(true);
        subject.setLastMenuSelectedAppFontFamily("Inter");
        subject.setLastMenuSelectedAppFontSize(15);
        subject.setLastMenuSelectedCodeFontFamily("JetBrains Mono");

        assertThat(subject.fontMenuBuilt()).isTrue();
        assertThat(subject.lastMenuSelectedAppFontFamily()).isEqualTo("Inter");
        assertThat(subject.lastMenuSelectedAppFontSize()).isEqualTo(15);
        assertThat(subject.lastMenuSelectedCodeFontFamily()).isEqualTo("JetBrains Mono");
    }
}
