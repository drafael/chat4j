package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenuBar;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MenuBarSettingDispatchCoordinatorTest {

    @Test
    @DisplayName("Apply delegates enabled flag and wires actions to provided callbacks")
    void apply_whenCalled_delegatesAndWiresActions() {
        var enabledRef = new AtomicBoolean();
        var actionsRef = new AtomicReference<MenuBarSettingCoordinator.MenuBarActions>();

        var subject = new MenuBarSettingDispatchCoordinator((enabled, actions) -> {
            enabledRef.set(enabled);
            actionsRef.set(actions);
        });

        var calls = new ArrayList<String>();
        JMenuBar menuBar = new JMenuBar();

        subject.apply(
                true,
                value -> calls.add("set-menu-bar:" + (value == null ? "null" : "menu")),
                () -> calls.add("ensure-menu"),
                () -> menuBar,
                () -> calls.add("ensure-themes"),
                () -> calls.add("ensure-models"),
                () -> calls.add("ensure-font"),
                () -> calls.add("sync-preview"),
                () -> calls.add("refresh")
        );

        assertThat(enabledRef.get()).isTrue();
        assertThat(actionsRef.get()).isNotNull();

        actionsRef.get().disableMenuBar().run();
        actionsRef.get().ensureMenuBar().run();
        actionsRef.get().installMenuBar().run();
        actionsRef.get().ensureThemesMenuReady().run();
        actionsRef.get().ensureModelsMenuReady().run();
        actionsRef.get().ensureFontMenuReady().run();
        actionsRef.get().syncTogglePreviewMenuSelection().run();
        actionsRef.get().refreshWindow().run();

        assertThat(calls).containsExactly(
                "set-menu-bar:null",
                "ensure-menu",
                "set-menu-bar:menu",
                "ensure-themes",
                "ensure-models",
                "ensure-font",
                "sync-preview",
                "refresh"
        );
    }

    @Test
    @DisplayName("Apply validates required arguments and constructor dependency")
    void apply_whenInvalidInput_throwsException() {
        var subject = new MenuBarSettingDispatchCoordinator((enabled, actions) -> {
        });

        assertThatThrownBy(() -> subject.apply(
                true,
                null,
                () -> {
                },
                JMenuBar::new,
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
                .hasMessageContaining("menuBarSetter must not be null");

        assertThatThrownBy(() -> new MenuBarSettingDispatchCoordinator(
                (MenuBarSettingDispatchCoordinator.ApplyAction) null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("applyAction must not be null");
    }
}
