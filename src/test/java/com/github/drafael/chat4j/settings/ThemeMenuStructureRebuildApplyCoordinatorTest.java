package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThemeMenuStructureRebuildApplyCoordinatorTest {

    @Test
    @DisplayName("Apply updates tracked themes-menu state and returns applied state")
    void apply_whenCalled_updatesStateAndReturnsRebuildState() {
        var subject = new ThemeMenuStructureRebuildApplyCoordinator();
        var rebuildState = new ThemeMenuStructureRebuildCoordinator.RebuildState(true, "Catppuccin");

        var themesMenuBuilt = new AtomicReference<Boolean>();
        var lastMenuSelectedTheme = new AtomicReference<String>();

        ThemeMenuStructureRebuildCoordinator.RebuildState applied = subject.apply(
                rebuildState,
                themesMenuBuilt::set,
                lastMenuSelectedTheme::set
        );

        assertThat(applied).isSameAs(rebuildState);
        assertThat(themesMenuBuilt.get()).isTrue();
        assertThat(lastMenuSelectedTheme.get()).isEqualTo("Catppuccin");
    }

    @Test
    @DisplayName("Apply validates required arguments")
    void apply_whenRequiredArgumentMissing_throwsException() {
        var subject = new ThemeMenuStructureRebuildApplyCoordinator();
        var rebuildState = new ThemeMenuStructureRebuildCoordinator.RebuildState(false, null);

        assertThatThrownBy(() -> subject.apply(null, value -> {
        }, value -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rebuildState");

        assertThatThrownBy(() -> subject.apply(rebuildState, null, value -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("setThemesMenuBuilt");

        assertThatThrownBy(() -> subject.apply(rebuildState, value -> {
        }, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("setLastMenuSelectedTheme");
    }
}
