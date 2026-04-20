package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FontMenuStructureRebuildApplyCoordinatorTest {

    @Test
    @DisplayName("Apply updates tracked font-menu rebuild state and returns applied state")
    void apply_whenCalled_updatesStateAndReturnsRebuildState() {
        var subject = new FontMenuStructureRebuildApplyCoordinator();
        var rebuildState = new FontMenuStructureRebuildCoordinator.RebuildState(
                true,
                "Inter",
                15,
                "JetBrains Mono"
        );

        var fontMenuBuilt = new AtomicReference<Boolean>();
        var lastMenuSelectedAppFontFamily = new AtomicReference<String>();
        var lastMenuSelectedAppFontSize = new AtomicReference<Integer>();
        var lastMenuSelectedCodeFontFamily = new AtomicReference<String>();

        FontMenuStructureRebuildCoordinator.RebuildState applied = subject.apply(
                rebuildState,
                fontMenuBuilt::set,
                lastMenuSelectedAppFontFamily::set,
                lastMenuSelectedAppFontSize::set,
                lastMenuSelectedCodeFontFamily::set
        );

        assertThat(applied).isSameAs(rebuildState);
        assertThat(fontMenuBuilt.get()).isTrue();
        assertThat(lastMenuSelectedAppFontFamily.get()).isEqualTo("Inter");
        assertThat(lastMenuSelectedAppFontSize.get()).isEqualTo(15);
        assertThat(lastMenuSelectedCodeFontFamily.get()).isEqualTo("JetBrains Mono");
    }

    @Test
    @DisplayName("Apply validates required arguments")
    void apply_whenRequiredArgumentMissing_throwsException() {
        var subject = new FontMenuStructureRebuildApplyCoordinator();
        var rebuildState = new FontMenuStructureRebuildCoordinator.RebuildState(false, null, null, null);

        assertThatThrownBy(() -> subject.apply(null, value -> {
        }, value -> {
        }, value -> {
        }, value -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rebuildState must not be null");

        assertThatThrownBy(() -> subject.apply(rebuildState, null, value -> {
        }, value -> {
        }, value -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("setFontMenuBuilt must not be null");

        assertThatThrownBy(() -> subject.apply(rebuildState, value -> {
        }, null, value -> {
        }, value -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("setLastMenuSelectedAppFontFamily must not be null");

        assertThatThrownBy(() -> subject.apply(rebuildState, value -> {
        }, value -> {
        }, null, value -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("setLastMenuSelectedAppFontSize must not be null");

        assertThatThrownBy(() -> subject.apply(rebuildState, value -> {
        }, value -> {
        }, value -> {
        }, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("setLastMenuSelectedCodeFontFamily must not be null");
    }
}
