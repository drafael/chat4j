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

class FontMenuStructureRebuildCoordinatorTest {

    @Test
    @DisplayName("Rebuild preserves state and skips action when font menu is absent")
    void rebuild_whenMenuMissing_preservesStateAndSkipsAction() {
        var actionCalls = new AtomicInteger();
        var subject = new FontMenuStructureRebuildCoordinator((
                fontMenu,
                appFontMenuItemsByFamily,
                appFontSizeMenuItemsBySize,
                codeFontMenuItemsByFamily,
                onRestoreAppFont,
                onIncreaseAppFontSize,
                onDecreaseAppFontSize,
                onAppFontFamilySelected,
                onCodeFontFamilySelected,
                onAppFontSizeSelected
        ) -> actionCalls.incrementAndGet());

        FontMenuStructureRebuildCoordinator.RebuildState state = subject.rebuild(
                null,
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                () -> {
                },
                () -> {
                },
                () -> {
                },
                font -> {
                },
                font -> {
                },
                size -> {
                },
                false,
                "Inter",
                14,
                "JetBrains Mono"
        );

        assertThat(actionCalls.get()).isZero();
        assertThat(state.fontMenuBuilt()).isFalse();
        assertThat(state.lastMenuSelectedAppFontFamily()).isEqualTo("Inter");
        assertThat(state.lastMenuSelectedAppFontSize()).isEqualTo(14);
        assertThat(state.lastMenuSelectedCodeFontFamily()).isEqualTo("JetBrains Mono");
    }

    @Test
    @DisplayName("Rebuild executes action and resets state when font menu is present")
    void rebuild_whenMenuPresent_executesActionAndResetsState() {
        var actionCalls = new AtomicInteger();
        var subject = new FontMenuStructureRebuildCoordinator((
                fontMenu,
                appFontMenuItemsByFamily,
                appFontSizeMenuItemsBySize,
                codeFontMenuItemsByFamily,
                onRestoreAppFont,
                onIncreaseAppFontSize,
                onDecreaseAppFontSize,
                onAppFontFamilySelected,
                onCodeFontFamilySelected,
                onAppFontSizeSelected
        ) -> actionCalls.incrementAndGet());

        Map<String, JRadioButtonMenuItem> appFontMenuItemsByFamily = new LinkedHashMap<>();
        Map<Integer, JRadioButtonMenuItem> appFontSizeMenuItemsBySize = new LinkedHashMap<>();
        Map<String, JRadioButtonMenuItem> codeFontMenuItemsByFamily = new LinkedHashMap<>();

        FontMenuStructureRebuildCoordinator.RebuildState state = subject.rebuild(
                new JMenu("Font"),
                appFontMenuItemsByFamily,
                appFontSizeMenuItemsBySize,
                codeFontMenuItemsByFamily,
                () -> {
                },
                () -> {
                },
                () -> {
                },
                font -> {
                },
                font -> {
                },
                size -> {
                },
                false,
                "Inter",
                14,
                "JetBrains Mono"
        );

        assertThat(actionCalls.get()).isEqualTo(1);
        assertThat(state.fontMenuBuilt()).isTrue();
        assertThat(state.lastMenuSelectedAppFontFamily()).isNull();
        assertThat(state.lastMenuSelectedAppFontSize()).isNull();
        assertThat(state.lastMenuSelectedCodeFontFamily()).isNull();
    }

    @Test
    @DisplayName("Rebuild validates required arguments")
    void rebuild_whenArgumentMissing_throwsException() {
        var subject = new FontMenuStructureRebuildCoordinator((
                fontMenu,
                appFontMenuItemsByFamily,
                appFontSizeMenuItemsBySize,
                codeFontMenuItemsByFamily,
                onRestoreAppFont,
                onIncreaseAppFontSize,
                onDecreaseAppFontSize,
                onAppFontFamilySelected,
                onCodeFontFamilySelected,
                onAppFontSizeSelected
        ) -> {
        });

        assertThatThrownBy(() -> subject.rebuild(
                new JMenu("Font"),
                null,
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                () -> {
                },
                () -> {
                },
                () -> {
                },
                font -> {
                },
                font -> {
                },
                size -> {
                },
                true,
                "Inter",
                14,
                "JetBrains Mono"
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("appFontMenuItemsByFamily");

        assertThatThrownBy(() -> new FontMenuStructureRebuildCoordinator(
                (FontMenuStructureRebuildCoordinator.RebuildAction) null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rebuildAction");
    }
}
