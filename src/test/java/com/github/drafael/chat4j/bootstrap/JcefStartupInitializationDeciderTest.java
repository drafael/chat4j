package com.github.drafael.chat4j.bootstrap;

import com.github.drafael.chat4j.chat.webview.WebViewEngine;
import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class JcefStartupInitializationDeciderTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("Linux default initializes JCEF at startup")
    void shouldInitialize_whenDefaultEngineOutsideMacOsAndWindows_returnsTrue() {
        var subject = decider(false, false, true);

        boolean result = subject.shouldInitialize(settingsRepo());

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("macOS default skips JCEF when System WebView is available")
    void shouldInitialize_whenDefaultMacOsSystemWebViewAvailable_returnsFalse() {
        var subject = decider(true, false, true);

        boolean result = subject.shouldInitialize(settingsRepo());

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("macOS default initializes JCEF when System WebView is unavailable")
    void shouldInitialize_whenDefaultMacOsSystemWebViewUnavailable_returnsTrue() {
        var subject = decider(true, false, false);

        boolean result = subject.shouldInitialize(settingsRepo());

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Configured JCEF initializes even when System WebView is available")
    void shouldInitialize_whenJcefConfigured_returnsTrue() throws Exception {
        SettingsRepository settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.WEBVIEW_ENGINE, WebViewEngine.JCEF.settingValue());
        var subject = decider(true, false, true);

        boolean result = subject.shouldInitialize(settingsRepo);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Configured System WebView skips JCEF when system engine is available")
    void shouldInitialize_whenSystemWebViewConfiguredAndAvailable_returnsFalse() throws Exception {
        SettingsRepository settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.WEBVIEW_ENGINE, WebViewEngine.SYSTEM.settingValue());
        var subject = decider(false, false, true);

        boolean result = subject.shouldInitialize(settingsRepo);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Configured System WebView initializes JCEF when system engine is unavailable")
    void shouldInitialize_whenSystemWebViewConfiguredAndUnavailable_returnsTrue() throws Exception {
        SettingsRepository settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.WEBVIEW_ENGINE, WebViewEngine.SYSTEM.settingValue());
        var subject = decider(false, false, false);

        boolean result = subject.shouldInitialize(settingsRepo);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Configured JEditorPane skips JCEF startup initialization")
    void shouldInitialize_whenJEditorPaneConfigured_returnsFalse() throws Exception {
        SettingsRepository settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.WEBVIEW_ENGINE, WebViewEngine.JEDITOR_PANE.settingValue());
        var subject = decider(false, false, false);

        boolean result = subject.shouldInitialize(settingsRepo);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Invalid engine setting uses the non-system platform default")
    void shouldInitialize_whenInvalidEngineConfiguredOutsideMacOsAndWindows_returnsTrue() throws Exception {
        SettingsRepository settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.WEBVIEW_ENGINE, "invalid");
        var subject = decider(false, false, false);

        boolean result = subject.shouldInitialize(settingsRepo);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Obsolete engine setting uses the macOS platform default")
    void shouldInitialize_whenObsoleteEngineConfiguredOnMacOsAndSystemWebViewAvailable_returnsFalse() throws Exception {
        SettingsRepository settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.WEBVIEW_ENGINE, "obsolete-webview");
        var subject = decider(true, false, true);

        boolean result = subject.shouldInitialize(settingsRepo);

        assertThat(result).isFalse();
    }

    private JcefStartupInitializationDecider decider(boolean macOs, boolean windows, boolean systemWebViewAvailable) {
        return new JcefStartupInitializationDecider(
                () -> macOs,
                () -> windows,
                () -> systemWebViewAvailable
        );
    }

    private SettingsRepository settingsRepo() {
        return new SettingsRepository(tempDir.resolve("settings.properties"));
    }
}
