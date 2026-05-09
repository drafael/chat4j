package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.JRadioButtonMenuItem;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThemeMenuStructureRebuildCoordinatorTest {

    @Test
    @DisplayName("Rebuild preserves state and skips action when themes menu is absent")
    void rebuild_whenMenuMissing_preservesStateAndSkipsAction() {
        var actionCalls = new AtomicInteger();
        var subject = new ThemeMenuStructureRebuildCoordinator((themesMenu, themeMenuItemsByName, onThemeSelected) ->
                actionCalls.incrementAndGet());

        ThemeMenuStructureRebuildCoordinator.RebuildState state = subject.rebuild(
                null,
                new LinkedHashMap<>(),
                (themeName, className) -> {
                },
                false,
                "GitHub"
        );

        assertThat(actionCalls.get()).isZero();
        assertThat(state.themesMenuBuilt()).isFalse();
        assertThat(state.lastMenuSelectedTheme()).isEqualTo("GitHub");
    }

    @Test
    @DisplayName("Rebuild executes action and resets state when themes menu is present")
    void rebuild_whenMenuPresent_executesActionAndResetsState() {
        var actionCalls = new AtomicInteger();
        var subject = new ThemeMenuStructureRebuildCoordinator((themesMenu, themeMenuItemsByName, onThemeSelected) ->
                actionCalls.incrementAndGet());
        Map<String, JRadioButtonMenuItem> themeMenuItemsByName = new LinkedHashMap<>();

        ThemeMenuStructureRebuildCoordinator.RebuildState state = subject.rebuild(
                new JMenu("Theme"),
                themeMenuItemsByName,
                (themeName, className) -> {
                },
                false,
                "GitHub"
        );

        assertThat(actionCalls.get()).isEqualTo(1);
        assertThat(state.themesMenuBuilt()).isTrue();
        assertThat(state.lastMenuSelectedTheme()).isNull();
    }

    @Test
    @DisplayName("Rebuild validates required arguments")
    void rebuild_whenArgumentMissing_throwsException() {
        var subject = new ThemeMenuStructureRebuildCoordinator((themesMenu, themeMenuItemsByName, onThemeSelected) -> {
        });

        assertThatThrownBy(() -> subject.rebuild(
                new JMenu("Theme"),
                null,
                (themeName, className) -> {
                },
                true,
                "GitHub"
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("themeMenuItemsByName");

        assertThatThrownBy(() -> new ThemeMenuStructureRebuildCoordinator(
                (ThemeMenuStructureRebuildCoordinator.RebuildAction) null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rebuildAction");
    }
}
