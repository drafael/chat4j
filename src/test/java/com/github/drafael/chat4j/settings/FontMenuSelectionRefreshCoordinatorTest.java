package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JRadioButtonMenuItem;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static java.util.Collections.emptyMap;

class FontMenuSelectionRefreshCoordinatorTest {

    @Test
    @DisplayName("Refresh resolves available font menu selections and delegates to synchronizer")
    void refresh_whenCalled_resolvesSelectionAndDelegatesToSynchronizer() {
        var capturedAppFamilies = new AtomicReference<Set<String>>();
        var capturedAppSizes = new AtomicReference<Set<Integer>>();
        var capturedCodeFamilies = new AtomicReference<Set<String>>();

        var currentSelection = new FontSettingsResolver.FontMenuSelection("Inter", 14, "Monospaced");

        var capturedCurrentSelection = new AtomicReference<FontSettingsResolver.FontMenuSelection>();
        var capturedPreviousSelection = new AtomicReference<FontMenuSelectionSynchronizer.FontMenuSelectionState>();
        var capturedFontMenuBuilt = new AtomicReference<Boolean>();
        var capturedAppItems = new AtomicReference<Map<String, JRadioButtonMenuItem>>();
        var capturedAppSizeItems = new AtomicReference<Map<Integer, JRadioButtonMenuItem>>();
        var capturedCodeItems = new AtomicReference<Map<String, JRadioButtonMenuItem>>();

        var expectedState = new FontMenuSelectionSynchronizer.FontMenuSelectionState("Inter", 14, "Monospaced");

        var subject = new FontMenuSelectionRefreshCoordinator(
                (availableAppFontFamilies, availableAppFontSizes, availableCodeFontFamilies) -> {
                    capturedAppFamilies.set(availableAppFontFamilies);
                    capturedAppSizes.set(availableAppFontSizes);
                    capturedCodeFamilies.set(availableCodeFontFamilies);
                    return currentSelection;
                },
                (appFontMenuItemsByFamily,
                 appFontSizeMenuItemsBySize,
                 codeFontMenuItemsByFamily,
                 resolvedSelection,
                 previousSelection,
                 fontMenuBuilt) -> {
                    capturedAppItems.set(appFontMenuItemsByFamily);
                    capturedAppSizeItems.set(appFontSizeMenuItemsBySize);
                    capturedCodeItems.set(codeFontMenuItemsByFamily);
                    capturedCurrentSelection.set(resolvedSelection);
                    capturedPreviousSelection.set(previousSelection);
                    capturedFontMenuBuilt.set(fontMenuBuilt);
                    return expectedState;
                }
        );

        var appItemsByFamily = new LinkedHashMap<String, JRadioButtonMenuItem>();
        appItemsByFamily.put("System Default", new JRadioButtonMenuItem("System Default"));
        appItemsByFamily.put("Inter", new JRadioButtonMenuItem("Inter"));

        var appSizeItemsBySize = new LinkedHashMap<Integer, JRadioButtonMenuItem>();
        appSizeItemsBySize.put(13, new JRadioButtonMenuItem("13"));
        appSizeItemsBySize.put(14, new JRadioButtonMenuItem("14"));

        var codeItemsByFamily = new LinkedHashMap<String, JRadioButtonMenuItem>();
        codeItemsByFamily.put("Monospaced", new JRadioButtonMenuItem("Monospaced"));

        var previousState = new FontMenuSelectionSynchronizer.FontMenuSelectionState("System Default", 13, "Monospaced");

        FontMenuSelectionSynchronizer.FontMenuSelectionState refreshedState = subject.refresh(
                appItemsByFamily,
                appSizeItemsBySize,
                codeItemsByFamily,
                previousState,
                true
        );

        assertThat(refreshedState).isEqualTo(expectedState);
        assertThat(capturedAppFamilies.get()).containsExactlyInAnyOrder("System Default", "Inter");
        assertThat(capturedAppSizes.get()).containsExactlyInAnyOrder(13, 14);
        assertThat(capturedCodeFamilies.get()).containsExactly("Monospaced");
        assertThat(capturedAppItems.get()).isSameAs(appItemsByFamily);
        assertThat(capturedAppSizeItems.get()).isSameAs(appSizeItemsBySize);
        assertThat(capturedCodeItems.get()).isSameAs(codeItemsByFamily);
        assertThat(capturedCurrentSelection.get()).isEqualTo(currentSelection);
        assertThat(capturedPreviousSelection.get()).isEqualTo(previousState);
        assertThat(capturedFontMenuBuilt.get()).isTrue();
    }

    @Test
    @DisplayName("Refresh validates required menu maps")
    void refresh_whenRequiredMapMissing_throwsException() {
        var subject = new FontMenuSelectionRefreshCoordinator(
                (availableAppFontFamilies, availableAppFontSizes, availableCodeFontFamilies) ->
                        new FontSettingsResolver.FontMenuSelection("Inter", 14, "Monospaced"),
                (appFontMenuItemsByFamily,
                 appFontSizeMenuItemsBySize,
                 codeFontMenuItemsByFamily,
                 resolvedSelection,
                 previousSelection,
                 fontMenuBuilt) -> previousSelection
        );

        assertThatThrownBy(() -> subject.refresh(
                null,
                emptyMap(),
                emptyMap(),
                new FontMenuSelectionSynchronizer.FontMenuSelectionState(null, null, null),
                true
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("appFontMenuItemsByFamily must not be null");
    }
}
