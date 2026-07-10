package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.webview.WebViewEngine;
import com.github.drafael.chat4j.chat.webview.WebViewRuntimeStatus;
import com.github.drafael.chat4j.chat.webview.WebViewSettings;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.awt.*;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class AppearancePanelTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        AppearancePanel.restoreAccentColor(settingsRepo("appearance-panel-reset-accent"));
    }

    @Test
    @DisplayName("Saved fonts are applied when all repository reads succeed")
    void applySavedFonts_whenReadsSucceed_appliesSavedAppAndCodeFonts() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-fonts-success");
        settingsRepo.put(FontSettings.APP_FONT_FAMILY_KEY, Font.SERIF);
        settingsRepo.put(FontSettings.APP_FONT_SIZE_KEY, "16");
        settingsRepo.put(FontSettings.CODE_FONT_FAMILY_KEY, Font.DIALOG_INPUT);

        AppearancePanel.applySavedFonts(settingsRepo);

        assertThat(UIManager.getFont("defaultFont").getFamily()).isEqualTo(Font.SERIF);
        assertThat(UIManager.getFont("defaultFont").getSize()).isEqualTo(16);
        assertThat(UIManager.getFont("monospaced.font").getFamily()).isEqualTo(Font.DIALOG_INPUT);
    }

    @Test
    @DisplayName("Saved fonts fall back as a unit when any repository read fails")
    void applySavedFonts_whenAnyReadFails_fallsBackAsUnit() {
        int fallbackSize = AppearancePanel.normalizeAppFontSize(AppearancePanel.defaultAppFontSize());
        int savedSize = differentFontSize(fallbackSize);
        var settingsRepo = new ThrowingFontSettingsRepo(savedSize);

        AppearancePanel.applySavedFonts(settingsRepo);

        assertThat(UIManager.getFont("defaultFont").getSize()).isEqualTo(fallbackSize);
    }

    @Test
    @DisplayName("Restore accent color falls back to default when stored hex is invalid")
    void restoreAccentColor_whenStoredHexIsInvalid_clearsAccentColor() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-accent-invalid");
        settingsRepo.put(ThemeSettings.THEME_ACCENT_KEY, "#007AFF");
        AppearancePanel.restoreAccentColor(settingsRepo);
        assertThat(AppearancePanel.currentAccentColor()).isEqualTo(Color.decode("#007AFF"));

        settingsRepo.put(ThemeSettings.THEME_ACCENT_KEY, "not-a-color");
        AppearancePanel.restoreAccentColor(settingsRepo);

        assertThat(AppearancePanel.currentAccentColor()).isNull();
    }

    @Test
    @DisplayName("Restore accent color falls back to default when repository access fails")
    void restoreAccentColor_whenRepositoryFails_clearsAccentColor() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-accent-read-failure");
        settingsRepo.put(ThemeSettings.THEME_ACCENT_KEY, "#007AFF");
        AppearancePanel.restoreAccentColor(settingsRepo);
        assertThat(AppearancePanel.currentAccentColor()).isNotNull();

        AppearancePanel.restoreAccentColor(new ThrowingSettingsRepo());

        assertThat(AppearancePanel.currentAccentColor()).isNull();
    }

    @Test
    @DisplayName("Accent selection writes concrete colors and removes the default accent")
    void applyAccentSelection_whenColorSelected_writesOrRemovesAccentKey() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-accent-selection");
        AppearancePanel subject = new AppearancePanel(settingsRepo, systemRuntimeStatus());

        subject.applyAccentSelection(Color.decode("#007AFF"), "#007AFF");
        assertThat(settingsRepo.get(ThemeSettings.THEME_ACCENT_KEY)).contains("#007AFF");

        subject.applyAccentSelection(null, null);
        assertThat(settingsRepo.get(ThemeSettings.THEME_ACCENT_KEY)).isEmpty();
    }

    @Test
    @DisplayName("Canceling a WebView engine change restores the active engine")
    void webViewEngineSelection_whenRestartPromptCanceled_restoresActiveEngine() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-webview-cancel");
        settingsRepo.put(WebViewSettings.ENGINE_KEY, WebViewEngine.SYSTEM.settingValue());
        var exitCalled = new AtomicBoolean(false);
        var promptMessage = new AtomicReference<String>();
        AppearancePanel subject = new AppearancePanel(
                settingsRepo,
                systemRuntimeStatus(),
                () -> exitCalled.set(true),
                (parent, message) -> {
                    promptMessage.set(message);
                    return RestartRequiredDialog.Choice.CANCEL;
                }
        );
        JComboBox<?> engineComboBox = findComponentByName(
                subject,
                "chatWebViewEngineComboBox",
                JComboBox.class
        );

        SwingUtilities.invokeAndWait(() -> engineComboBox.setSelectedItem(WebViewEngine.JCEF.settingValue()));

        assertThat(settingsRepo.get(WebViewSettings.ENGINE_KEY)).contains(WebViewEngine.SYSTEM.settingValue());
        assertThat(engineComboBox.getSelectedItem()).isEqualTo(WebViewEngine.SYSTEM.settingValue());
        assertThat(exitCalled).isFalse();
        assertThat(promptMessage.get()).contains("System WebView", "Chromium Embedded Framework");
    }

    @Test
    @DisplayName("Canceling a WebView engine change during fallback preserves the configured engine")
    void webViewEngineSelection_whenFallbackPromptCanceled_restoresConfiguredEngine() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-webview-fallback-cancel");
        settingsRepo.put(WebViewSettings.ENGINE_KEY, WebViewEngine.SYSTEM.settingValue());
        var exitCalled = new AtomicBoolean(false);
        var promptCount = new AtomicInteger();
        AppearancePanel subject = new AppearancePanel(
                settingsRepo,
                fallbackRuntimeStatus(),
                () -> exitCalled.set(true),
                (parent, message) -> {
                    promptCount.incrementAndGet();
                    return RestartRequiredDialog.Choice.CANCEL;
                }
        );
        JComboBox<?> engineComboBox = findComponentByName(
                subject,
                "chatWebViewEngineComboBox",
                JComboBox.class
        );

        SwingUtilities.invokeAndWait(() -> engineComboBox.setSelectedItem(WebViewEngine.JCEF.settingValue()));

        assertThat(settingsRepo.get(WebViewSettings.ENGINE_KEY)).contains(WebViewEngine.SYSTEM.settingValue());
        assertThat(engineComboBox.getSelectedItem()).isEqualTo(WebViewEngine.SYSTEM.settingValue());
        assertThat(exitCalled).isFalse();
        assertThat(promptCount).hasValue(1);
    }

    @Test
    @DisplayName("Choosing later keeps the WebView engine change for the next launch")
    void webViewEngineSelection_whenRestartPromptDeferred_keepsSelectedEngine() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-webview-later");
        settingsRepo.put(WebViewSettings.ENGINE_KEY, WebViewEngine.SYSTEM.settingValue());
        var exitCalled = new AtomicBoolean(false);
        AppearancePanel subject = new AppearancePanel(
                settingsRepo,
                systemRuntimeStatus(),
                () -> exitCalled.set(true),
                (parent, message) -> RestartRequiredDialog.Choice.LATER
        );
        JComboBox<?> engineComboBox = findComponentByName(
                subject,
                "chatWebViewEngineComboBox",
                JComboBox.class
        );

        SwingUtilities.invokeAndWait(() -> engineComboBox.setSelectedItem(WebViewEngine.JCEF.settingValue()));

        assertThat(settingsRepo.get(WebViewSettings.ENGINE_KEY)).contains(WebViewEngine.JCEF.settingValue());
        assertThat(engineComboBox.getSelectedItem()).isEqualTo(WebViewEngine.JCEF.settingValue());
        assertThat(exitCalled).isFalse();
    }

    @Test
    @DisplayName("Choosing exit now saves the WebView engine change and exits")
    void webViewEngineSelection_whenRestartPromptAccepted_savesSelectedEngineAndRunsExitAction() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-webview-exit-now");
        settingsRepo.put(WebViewSettings.ENGINE_KEY, WebViewEngine.SYSTEM.settingValue());
        var exitCalled = new AtomicBoolean(false);
        AppearancePanel subject = new AppearancePanel(
                settingsRepo,
                systemRuntimeStatus(),
                () -> exitCalled.set(true),
                (parent, message) -> RestartRequiredDialog.Choice.EXIT_NOW
        );
        JComboBox<?> engineComboBox = findComponentByName(
                subject,
                "chatWebViewEngineComboBox",
                JComboBox.class
        );

        SwingUtilities.invokeAndWait(() -> engineComboBox.setSelectedItem(WebViewEngine.JCEF.settingValue()));

        assertThat(settingsRepo.get(WebViewSettings.ENGINE_KEY)).contains(WebViewEngine.JCEF.settingValue());
        assertThat(engineComboBox.getSelectedItem()).isEqualTo(WebViewEngine.JCEF.settingValue());
        assertThat(exitCalled).isTrue();
    }

    private int differentFontSize(int fallbackSize) {
        return java.util.Arrays.stream(AppearancePanel.appFontSizeOptions())
                .filter(size -> size != fallbackSize)
                .findFirst()
                .orElse(fallbackSize + 4);
    }

    private SettingsRepository settingsRepo(String testName) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(testName)));
    }

    private WebViewRuntimeStatus systemRuntimeStatus() {
        return new WebViewRuntimeStatus(
                WebViewEngine.SYSTEM,
                WebViewEngine.SYSTEM,
                true,
                "HEAVYWEIGHT",
                true,
                "Windowed/native",
                ""
        );
    }

    private WebViewRuntimeStatus fallbackRuntimeStatus() {
        return new WebViewRuntimeStatus(
                WebViewEngine.SYSTEM,
                WebViewEngine.JEDITOR_PANE,
                false,
                "Unavailable",
                false,
                "Unavailable",
                "System WebView unavailable: missing runtime"
        );
    }

    private <T extends Component> T findComponentByName(Container root, String name, Class<T> type) {
        T found = findComponentByNameOrNull(root, name, type);
        assertThat(found).isNotNull();
        return found;
    }

    private <T extends Component> T findComponentByNameOrNull(Container root, String name, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (name.equals(component.getName()) && type.isInstance(component)) {
                return type.cast(component);
            }

            if (component instanceof Container container) {
                T found = findComponentByNameOrNull(container, name, type);
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private ThrowingSettingsRepo() {
            super(Path.of("unused-appearance-panel.properties"));
        }

        @Override
        public String get(String key, String defaultValue) {
            throw new IllegalStateException("forced failure");
        }
    }

    private static class ThrowingFontSettingsRepo extends SettingsRepository {

        private final int savedSize;

        private ThrowingFontSettingsRepo(int savedSize) {
            super(Path.of("unused-appearance-panel-fonts.properties"));
            this.savedSize = savedSize;
        }

        @Override
        public String get(String key, String defaultValue) {
            if (FontSettings.APP_FONT_FAMILY_KEY.equals(key)) {
                return Font.SERIF;
            }
            if (FontSettings.APP_FONT_SIZE_KEY.equals(key)) {
                return String.valueOf(savedSize);
            }
            if (FontSettings.CODE_FONT_FAMILY_KEY.equals(key)) {
                throw new IllegalStateException("forced failure");
            }
            return defaultValue;
        }
    }
}
