package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JRadioButtonMenuItem;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MenuSelectionSyncFlowIntegrationTest {

    @Test
    @DisplayName("Theme selection flow refreshes menu checks and persists last selected theme")
    void refreshAndApply_whenThemeChanges_updatesMenuChecksAndLastSelectedState() {
        var previousThemeItem = new JRadioButtonMenuItem("GitHub", true);
        var currentThemeItem = new JRadioButtonMenuItem("Dark");
        Map<String, JRadioButtonMenuItem> themeMenuItemsByName = new LinkedHashMap<>();
        themeMenuItemsByName.put("GitHub", previousThemeItem);
        themeMenuItemsByName.put("Dark", currentThemeItem);

        var refreshCoordinator = new ThemeMenuSelectionRefreshCoordinator(
                defaultTheme -> "Dark",
                new ThemeMenuSelectionSynchronizer()::syncSelection
        );
        var dispatchCoordinator = new ThemeMenuSelectionDispatchCoordinator(refreshCoordinator);
        var flowCoordinator = new ThemeMenuSelectionFlowCoordinator(
                dispatchCoordinator,
                new ThemeMenuSelectionApplyCoordinator()
        );
        var lastSelectedTheme = new AtomicReference<>("GitHub");

        String selectedTheme = flowCoordinator.refreshAndApply(
                themeMenuItemsByName,
                lastSelectedTheme.get(),
                true,
                "GitHub",
                lastSelectedTheme::set
        );

        assertThat(selectedTheme).isEqualTo("Dark");
        assertThat(lastSelectedTheme.get()).isEqualTo("Dark");
        assertThat(previousThemeItem.isSelected()).isFalse();
        assertThat(currentThemeItem.isSelected()).isTrue();
    }

    @Test
    @DisplayName("Font selection flow refreshes app and code font menu checks and persisted selection state")
    void refreshAndApply_whenFontSelectionChanges_updatesMenuChecksAndLastSelectedState() {
        var previousAppFontItem = new JRadioButtonMenuItem("Inter", true);
        var currentAppFontItem = new JRadioButtonMenuItem("SF Pro");
        Map<String, JRadioButtonMenuItem> appFontMenuItemsByFamily = new LinkedHashMap<>();
        appFontMenuItemsByFamily.put("Inter", previousAppFontItem);
        appFontMenuItemsByFamily.put("SF Pro", currentAppFontItem);

        var previousAppSizeItem = new JRadioButtonMenuItem("14", true);
        var currentAppSizeItem = new JRadioButtonMenuItem("16");
        Map<Integer, JRadioButtonMenuItem> appFontSizeMenuItemsBySize = new LinkedHashMap<>();
        appFontSizeMenuItemsBySize.put(14, previousAppSizeItem);
        appFontSizeMenuItemsBySize.put(16, currentAppSizeItem);

        var previousCodeFontItem = new JRadioButtonMenuItem("JetBrains Mono", true);
        var currentCodeFontItem = new JRadioButtonMenuItem("Fira Code");
        Map<String, JRadioButtonMenuItem> codeFontMenuItemsByFamily = new LinkedHashMap<>();
        codeFontMenuItemsByFamily.put("JetBrains Mono", previousCodeFontItem);
        codeFontMenuItemsByFamily.put("Fira Code", currentCodeFontItem);

        var refreshCoordinator = new FontMenuSelectionRefreshCoordinator(
                (availableAppFonts, availableAppSizes, availableCodeFonts) ->
                        new FontSettingsResolver.FontMenuSelection("SF Pro", 16, "Fira Code"),
                new FontMenuSelectionSynchronizer()::syncSelection
        );
        var dispatchCoordinator = new FontMenuSelectionDispatchCoordinator(refreshCoordinator);
        var flowCoordinator = new FontMenuSelectionFlowCoordinator(
                dispatchCoordinator,
                new FontMenuSelectionApplyCoordinator()
        );

        var lastAppFontFamily = new AtomicReference<>("Inter");
        var lastAppFontSize = new AtomicReference<>(14);
        var lastCodeFontFamily = new AtomicReference<>("JetBrains Mono");

        FontMenuSelectionSynchronizer.FontMenuSelectionState selectedFontState = flowCoordinator.refreshAndApply(
                appFontMenuItemsByFamily,
                appFontSizeMenuItemsBySize,
                codeFontMenuItemsByFamily,
                lastAppFontFamily.get(),
                lastAppFontSize.get(),
                lastCodeFontFamily.get(),
                true,
                lastAppFontFamily::set,
                lastAppFontSize::set,
                lastCodeFontFamily::set
        );

        assertThat(selectedFontState).isEqualTo(new FontMenuSelectionSynchronizer.FontMenuSelectionState("SF Pro", 16, "Fira Code"));
        assertThat(lastAppFontFamily.get()).isEqualTo("SF Pro");
        assertThat(lastAppFontSize.get()).isEqualTo(16);
        assertThat(lastCodeFontFamily.get()).isEqualTo("Fira Code");

        assertThat(previousAppFontItem.isSelected()).isFalse();
        assertThat(currentAppFontItem.isSelected()).isTrue();
        assertThat(previousAppSizeItem.isSelected()).isFalse();
        assertThat(currentAppSizeItem.isSelected()).isTrue();
        assertThat(previousCodeFontItem.isSelected()).isFalse();
        assertThat(currentCodeFontItem.isSelected()).isTrue();
    }
}
