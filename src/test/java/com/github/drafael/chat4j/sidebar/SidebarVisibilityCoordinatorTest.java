package com.github.drafael.chat4j.sidebar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SidebarVisibilityCoordinatorTest {

    private final SidebarVisibilityCoordinator subject = new SidebarVisibilityCoordinator();

    @Test
    @DisplayName("Toggle hides sidebar and stores current divider location when currently visible")
    void toggle_whenSidebarVisible_hidesSidebarAndStoresCurrentDividerLocation() {
        SidebarVisibilityCoordinator.ToggleResult result = subject.toggle(true, 250, 320);

        assertThat(result.sidebarVisible()).isFalse();
        assertThat(result.lastDividerLocation()).isEqualTo(320);
        assertThat(result.dividerLocation()).isZero();
        assertThat(result.dividerSize()).isZero();
    }

    @Test
    @DisplayName("Toggle shows sidebar using stored divider location when currently hidden")
    void toggle_whenSidebarHidden_restoresStoredDividerLocationAndVisibleDivider() {
        SidebarVisibilityCoordinator.ToggleResult result = subject.toggle(false, 275, 0);

        assertThat(result.sidebarVisible()).isTrue();
        assertThat(result.lastDividerLocation()).isEqualTo(275);
        assertThat(result.dividerLocation()).isEqualTo(275);
        assertThat(result.dividerSize()).isEqualTo(1);
    }
}
