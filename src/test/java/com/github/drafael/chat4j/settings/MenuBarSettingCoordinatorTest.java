package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MenuBarSettingCoordinatorTest {

    @Test
    @DisplayName("Apply disables menu bar and refreshes window when setting is off")
    void apply_whenDisabled_disablesMenuBarAndRefreshesWindow() {
        var subject = new MenuBarSettingCoordinator();
        var calls = new ArrayList<String>();

        subject.apply(false, actions(calls));

        assertThat(calls).containsExactly("disable", "refresh");
    }

    @Test
    @DisplayName("Apply enables menu bar and refreshes all dependent menu state when setting is on")
    void apply_whenEnabled_enablesMenuBarAndRefreshesAllMenuState() {
        var subject = new MenuBarSettingCoordinator();
        var calls = new ArrayList<String>();

        subject.apply(true, actions(calls));

        assertThat(calls).containsExactly(
                "ensure-menu-bar",
                "install-menu-bar",
                "themes-ready",
                "models-ready",
                "font-ready",
                "sync-preview",
                "refresh"
        );
    }

    @Test
    @DisplayName("Apply validates actions argument")
    void apply_whenActionsMissing_throwsException() {
        var subject = new MenuBarSettingCoordinator();

        assertThatThrownBy(() -> subject.apply(true, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actions");
    }

    @Test
    @DisplayName("MenuBarActions validates required callbacks")
    void menuBarActions_whenCallbackMissing_throwsException() {
        assertThatThrownBy(() -> new MenuBarSettingCoordinator.MenuBarActions(
                null,
                () -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("disableMenuBar");
    }

    private MenuBarSettingCoordinator.MenuBarActions actions(List<String> calls) {
        return new MenuBarSettingCoordinator.MenuBarActions(
                () -> calls.add("disable"),
                () -> calls.add("ensure-menu-bar"),
                () -> calls.add("install-menu-bar"),
                () -> calls.add("themes-ready"),
                () -> calls.add("models-ready"),
                () -> calls.add("font-ready"),
                () -> calls.add("sync-preview"),
                () -> calls.add("refresh")
        );
    }
}
