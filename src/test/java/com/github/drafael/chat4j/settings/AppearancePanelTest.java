package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.webview.WebViewEngine;
import com.github.drafael.chat4j.chat.webview.WebViewRuntimeStatus;
import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.awt.*;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class AppearancePanelTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Canceling a WebView engine change restores the active engine")
    void webViewEngineSelection_whenRestartPromptCanceled_restoresActiveEngine() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-webview-cancel");
        settingsRepo.put(SettingsKeys.WEBVIEW_ENGINE, WebViewEngine.SYSTEM.settingValue());
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

        assertThat(settingsRepo.get(SettingsKeys.WEBVIEW_ENGINE)).contains(WebViewEngine.SYSTEM.settingValue());
        assertThat(engineComboBox.getSelectedItem()).isEqualTo(WebViewEngine.SYSTEM.settingValue());
        assertThat(exitCalled).isFalse();
        assertThat(promptMessage.get()).contains("System WebView", "Chromium Embedded Framework");
    }

    @Test
    @DisplayName("Canceling a WebView engine change during fallback preserves the configured engine")
    void webViewEngineSelection_whenFallbackPromptCanceled_restoresConfiguredEngine() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-webview-fallback-cancel");
        settingsRepo.put(SettingsKeys.WEBVIEW_ENGINE, WebViewEngine.SYSTEM.settingValue());
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

        assertThat(settingsRepo.get(SettingsKeys.WEBVIEW_ENGINE)).contains(WebViewEngine.SYSTEM.settingValue());
        assertThat(engineComboBox.getSelectedItem()).isEqualTo(WebViewEngine.SYSTEM.settingValue());
        assertThat(exitCalled).isFalse();
        assertThat(promptCount).hasValue(1);
    }

    @Test
    @DisplayName("Choosing later keeps the WebView engine change for the next launch")
    void webViewEngineSelection_whenRestartPromptDeferred_keepsSelectedEngine() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-webview-later");
        settingsRepo.put(SettingsKeys.WEBVIEW_ENGINE, WebViewEngine.SYSTEM.settingValue());
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

        assertThat(settingsRepo.get(SettingsKeys.WEBVIEW_ENGINE)).contains(WebViewEngine.JCEF.settingValue());
        assertThat(engineComboBox.getSelectedItem()).isEqualTo(WebViewEngine.JCEF.settingValue());
        assertThat(exitCalled).isFalse();
    }

    @Test
    @DisplayName("Choosing exit now saves the WebView engine change and exits")
    void webViewEngineSelection_whenRestartPromptAccepted_savesSelectedEngineAndRunsExitAction() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-webview-exit-now");
        settingsRepo.put(SettingsKeys.WEBVIEW_ENGINE, WebViewEngine.SYSTEM.settingValue());
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

        assertThat(settingsRepo.get(SettingsKeys.WEBVIEW_ENGINE)).contains(WebViewEngine.JCEF.settingValue());
        assertThat(engineComboBox.getSelectedItem()).isEqualTo(WebViewEngine.JCEF.settingValue());
        assertThat(exitCalled).isTrue();
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
}
