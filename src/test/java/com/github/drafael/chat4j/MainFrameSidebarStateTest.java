package com.github.drafael.chat4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MainFrameSidebarStateTest {

    @Test
    @DisplayName("Default constructor starts with visible sidebar and default divider location")
    void constructor_whenDefault_startsWithVisibleSidebarAndDefaultDivider() {
        var subject = new MainFrameSidebarState();

        assertThat(subject.sidebarVisible()).isTrue();
        assertThat(subject.lastDividerLocation()).isEqualTo(250);
    }

    @Test
    @DisplayName("Setters update tracked sidebar state")
    void stateMutators_whenCalled_updateTrackedState() {
        var subject = new MainFrameSidebarState(true, 250);

        subject.setSidebarVisible(false);
        subject.setLastDividerLocation(320);

        assertThat(subject.sidebarVisible()).isFalse();
        assertThat(subject.lastDividerLocation()).isEqualTo(320);
    }
}
