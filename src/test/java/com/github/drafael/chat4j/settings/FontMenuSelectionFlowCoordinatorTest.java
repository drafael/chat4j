package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JRadioButtonMenuItem;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static java.util.Collections.emptyMap;

class FontMenuSelectionFlowCoordinatorTest {

    @Test
    @DisplayName("Refresh and apply delegates to refresh and apply actions")
    void refreshAndApply_whenCalled_delegatesAndReturnsAppliedSelection() {
        var capturedLastAppFamily = new AtomicReference<String>();
        var capturedLastAppSize = new AtomicReference<Integer>();
        var capturedLastCodeFamily = new AtomicReference<String>();
        var capturedSyncedSelection = new AtomicReference<FontMenuSelectionSynchronizer.FontMenuSelectionState>();

        var syncedSelection = new FontMenuSelectionSynchronizer.FontMenuSelectionState("Inter", 15, "JetBrains Mono");

        var subject = new FontMenuSelectionFlowCoordinator(
                (appFontMenuItemsByFamily,
                 appFontSizeMenuItemsBySize,
                 codeFontMenuItemsByFamily,
                 lastMenuSelectedAppFontFamily,
                 lastMenuSelectedAppFontSize,
                 lastMenuSelectedCodeFontFamily,
                 fontMenuBuilt) -> {
                    capturedLastAppFamily.set(lastMenuSelectedAppFontFamily);
                    capturedLastAppSize.set(lastMenuSelectedAppFontSize);
                    capturedLastCodeFamily.set(lastMenuSelectedCodeFontFamily);
                    return syncedSelection;
                },
                (selectionState,
                 setLastMenuSelectedAppFontFamily,
                 setLastMenuSelectedAppFontSize,
                 setLastMenuSelectedCodeFontFamily) -> {
                    capturedSyncedSelection.set(selectionState);
                    setLastMenuSelectedAppFontFamily.accept(selectionState.appFontFamily());
                    setLastMenuSelectedAppFontSize.accept(selectionState.appFontSize());
                    setLastMenuSelectedCodeFontFamily.accept(selectionState.codeFontFamily());
                    return selectionState;
                }
        );

        var appFontMenuItemsByFamily = new LinkedHashMap<String, JRadioButtonMenuItem>();
        var appFontSizeMenuItemsBySize = new LinkedHashMap<Integer, JRadioButtonMenuItem>();
        var codeFontMenuItemsByFamily = new LinkedHashMap<String, JRadioButtonMenuItem>();

        var appliedAppFamily = new AtomicReference<String>();
        var appliedAppSize = new AtomicReference<Integer>();
        var appliedCodeFamily = new AtomicReference<String>();

        FontMenuSelectionSynchronizer.FontMenuSelectionState applied = subject.refreshAndApply(
                appFontMenuItemsByFamily,
                appFontSizeMenuItemsBySize,
                codeFontMenuItemsByFamily,
                "System Default",
                14,
                "Monospaced",
                true,
                appliedAppFamily::set,
                appliedAppSize::set,
                appliedCodeFamily::set
        );

        assertThat(capturedLastAppFamily.get()).isEqualTo("System Default");
        assertThat(capturedLastAppSize.get()).isEqualTo(14);
        assertThat(capturedLastCodeFamily.get()).isEqualTo("Monospaced");
        assertThat(capturedSyncedSelection.get()).isSameAs(syncedSelection);
        assertThat(applied).isSameAs(syncedSelection);
        assertThat(appliedAppFamily.get()).isEqualTo("Inter");
        assertThat(appliedAppSize.get()).isEqualTo(15);
        assertThat(appliedCodeFamily.get()).isEqualTo("JetBrains Mono");
    }

    @Test
    @DisplayName("Refresh and apply validates required arguments and collaborators")
    void refreshAndApply_whenRequiredArgumentMissing_throwsException() {
        var subject = new FontMenuSelectionFlowCoordinator(
                (appFontMenuItemsByFamily,
                 appFontSizeMenuItemsBySize,
                 codeFontMenuItemsByFamily,
                 lastMenuSelectedAppFontFamily,
                 lastMenuSelectedAppFontSize,
                 lastMenuSelectedCodeFontFamily,
                 fontMenuBuilt) -> new FontMenuSelectionSynchronizer.FontMenuSelectionState(
                        lastMenuSelectedAppFontFamily,
                        lastMenuSelectedAppFontSize,
                        lastMenuSelectedCodeFontFamily
                ),
                (selectionState,
                 setLastMenuSelectedAppFontFamily,
                 setLastMenuSelectedAppFontSize,
                 setLastMenuSelectedCodeFontFamily) -> selectionState
        );

        assertThatThrownBy(() -> subject.refreshAndApply(
                null,
                emptyMap(),
                emptyMap(),
                null,
                null,
                null,
                true,
                value -> {
                },
                value -> {
                },
                value -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("appFontMenuItemsByFamily must not be null");

        assertThatThrownBy(() -> subject.refreshAndApply(
                emptyMap(),
                emptyMap(),
                emptyMap(),
                null,
                null,
                null,
                true,
                null,
                value -> {
                },
                value -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("setLastMenuSelectedAppFontFamily must not be null");

        assertThatThrownBy(() -> new FontMenuSelectionFlowCoordinator(
                (FontMenuSelectionFlowCoordinator.RefreshAction) null,
                (selectionState,
                 setLastMenuSelectedAppFontFamily,
                 setLastMenuSelectedAppFontSize,
                 setLastMenuSelectedCodeFontFamily) -> selectionState
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("refreshAction must not be null");
    }
}
