package com.github.drafael.chat4j.sidebar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SidebarToggleStateApplyCoordinatorTest {

    @Test
    @DisplayName("Apply updates tracked sidebar state and returns applied state")
    void apply_whenCalled_updatesStateAndReturnsToggleState() {
        var subject = new SidebarToggleStateApplyCoordinator();
        var toggleState = new SidebarToggleCoordinator.ToggleState(false, 320);

        var sidebarVisible = new AtomicReference<Boolean>();
        var lastDividerLocation = new AtomicReference<Integer>();

        SidebarToggleCoordinator.ToggleState applied = subject.apply(
                toggleState,
                sidebarVisible::set,
                lastDividerLocation::set
        );

        assertThat(applied).isSameAs(toggleState);
        assertThat(sidebarVisible.get()).isFalse();
        assertThat(lastDividerLocation.get()).isEqualTo(320);
    }

    @Test
    @DisplayName("Apply validates required arguments")
    void apply_whenRequiredArgumentMissing_throwsException() {
        var subject = new SidebarToggleStateApplyCoordinator();
        var toggleState = new SidebarToggleCoordinator.ToggleState(true, 250);

        assertThatThrownBy(() -> subject.apply(null, value -> {
        }, value -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("toggleState");

        assertThatThrownBy(() -> subject.apply(toggleState, null, value -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("setSidebarVisible");

        assertThatThrownBy(() -> subject.apply(toggleState, value -> {
        }, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("setLastDividerLocation");
    }
}
