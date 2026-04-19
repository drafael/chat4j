package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JRadioButtonMenuItem;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FontMenuSelectionSynchronizerTest {

    private final FontMenuSelectionSynchronizer subject = new FontMenuSelectionSynchronizer();

    @Test
    @DisplayName("Sync selection updates app/code font and size menu items")
    void syncSelection_whenSelectionChanges_updatesMenuItemsAndReturnsCurrentState() {
        var previousAppFont = new JRadioButtonMenuItem("System Default");
        previousAppFont.setSelected(true);
        var currentAppFont = new JRadioButtonMenuItem("Inter");

        var previousAppSize = new JRadioButtonMenuItem("14");
        previousAppSize.setSelected(true);
        var currentAppSize = new JRadioButtonMenuItem("16");

        var previousCodeFont = new JRadioButtonMenuItem("Monospaced");
        previousCodeFont.setSelected(true);
        var currentCodeFont = new JRadioButtonMenuItem("JetBrains Mono");

        Map<String, JRadioButtonMenuItem> appFonts = new LinkedHashMap<>();
        appFonts.put("System Default", previousAppFont);
        appFonts.put("Inter", currentAppFont);

        Map<Integer, JRadioButtonMenuItem> appSizes = new LinkedHashMap<>();
        appSizes.put(14, previousAppSize);
        appSizes.put(16, currentAppSize);

        Map<String, JRadioButtonMenuItem> codeFonts = new LinkedHashMap<>();
        codeFonts.put("Monospaced", previousCodeFont);
        codeFonts.put("JetBrains Mono", currentCodeFont);

        FontSettingsResolver.FontMenuSelection currentSelection =
                new FontSettingsResolver.FontMenuSelection("Inter", 16, "JetBrains Mono");

        FontMenuSelectionSynchronizer.FontMenuSelectionState state = subject.syncSelection(
                appFonts,
                appSizes,
                codeFonts,
                currentSelection,
                new FontMenuSelectionSynchronizer.FontMenuSelectionState("System Default", 14, "Monospaced"),
                true
        );

        assertThat(previousAppFont.isSelected()).isFalse();
        assertThat(previousAppSize.isSelected()).isFalse();
        assertThat(previousCodeFont.isSelected()).isFalse();
        assertThat(currentAppFont.isSelected()).isTrue();
        assertThat(currentAppSize.isSelected()).isTrue();
        assertThat(currentCodeFont.isSelected()).isTrue();
        assertThat(state).isEqualTo(new FontMenuSelectionSynchronizer.FontMenuSelectionState("Inter", 16, "JetBrains Mono"));
    }

    @Test
    @DisplayName("Sync selection keeps previous state when font menu is not built")
    void syncSelection_whenMenuNotBuilt_returnsPreviousStateWithoutMutatingItems() {
        var selectedItem = new JRadioButtonMenuItem("System Default");
        selectedItem.setSelected(true);

        Map<String, JRadioButtonMenuItem> appFonts = Map.of("System Default", selectedItem);
        Map<Integer, JRadioButtonMenuItem> appSizes = Map.of(14, new JRadioButtonMenuItem("14"));
        Map<String, JRadioButtonMenuItem> codeFonts = Map.of("Monospaced", new JRadioButtonMenuItem("Monospaced"));

        FontMenuSelectionSynchronizer.FontMenuSelectionState previousState =
                new FontMenuSelectionSynchronizer.FontMenuSelectionState("System Default", 14, "Monospaced");

        FontMenuSelectionSynchronizer.FontMenuSelectionState state = subject.syncSelection(
                appFonts,
                appSizes,
                codeFonts,
                new FontSettingsResolver.FontMenuSelection("Inter", 16, "JetBrains Mono"),
                previousState,
                false
        );

        assertThat(selectedItem.isSelected()).isTrue();
        assertThat(state).isEqualTo(previousState);
    }

    @Test
    @DisplayName("Sync selection keeps previous state when selection is unchanged")
    void syncSelection_whenSelectionUnchanged_returnsPreviousState() {
        var selectedItem = new JRadioButtonMenuItem("Inter");
        selectedItem.setSelected(true);

        Map<String, JRadioButtonMenuItem> appFonts = Map.of("Inter", selectedItem);
        Map<Integer, JRadioButtonMenuItem> appSizes = Map.of(16, new JRadioButtonMenuItem("16"));
        Map<String, JRadioButtonMenuItem> codeFonts = Map.of("JetBrains Mono", new JRadioButtonMenuItem("JetBrains Mono"));

        FontMenuSelectionSynchronizer.FontMenuSelectionState previousState =
                new FontMenuSelectionSynchronizer.FontMenuSelectionState("Inter", 16, "JetBrains Mono");

        FontMenuSelectionSynchronizer.FontMenuSelectionState state = subject.syncSelection(
                appFonts,
                appSizes,
                codeFonts,
                new FontSettingsResolver.FontMenuSelection("Inter", 16, "JetBrains Mono"),
                previousState,
                true
        );

        assertThat(selectedItem.isSelected()).isTrue();
        assertThat(state).isEqualTo(previousState);
    }
}
