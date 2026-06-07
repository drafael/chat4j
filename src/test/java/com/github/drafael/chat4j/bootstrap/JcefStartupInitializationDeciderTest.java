package com.github.drafael.chat4j.bootstrap;

import com.github.drafael.chat4j.chat.message.ChatWebViewEngine;
import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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
    @DisplayName("macOS default skips JCEF when Native WebView is available")
    void shouldInitialize_whenDefaultMacOsNativeWebViewAvailable_returnsFalse() {
        var subject = decider(true, false, true);

        boolean result = subject.shouldInitialize(settingsRepo());

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("macOS default initializes JCEF when Native WebView is unavailable")
    void shouldInitialize_whenDefaultMacOsNativeWebViewUnavailable_returnsTrue() {
        var subject = decider(true, false, false);

        boolean result = subject.shouldInitialize(settingsRepo());

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Configured JCEF initializes even when Native WebView is available")
    void shouldInitialize_whenJcefConfigured_returnsTrue() throws Exception {
        SettingsRepo settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.CHAT_WEB_VIEW_ENGINE, ChatWebViewEngine.JCEF.settingValue());
        var subject = decider(true, false, true);

        boolean result = subject.shouldInitialize(settingsRepo);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Configured Native WebView skips JCEF when native engine is available")
    void shouldInitialize_whenNativeWebViewConfiguredAndAvailable_returnsFalse() throws Exception {
        SettingsRepo settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.CHAT_WEB_VIEW_ENGINE, ChatWebViewEngine.NATIVE_WEBVIEW.settingValue());
        var subject = decider(false, false, true);

        boolean result = subject.shouldInitialize(settingsRepo);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Configured Native WebView initializes JCEF when native engine is unavailable")
    void shouldInitialize_whenNativeWebViewConfiguredAndUnavailable_returnsTrue() throws Exception {
        SettingsRepo settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.CHAT_WEB_VIEW_ENGINE, ChatWebViewEngine.NATIVE_WEBVIEW.settingValue());
        var subject = decider(false, false, false);

        boolean result = subject.shouldInitialize(settingsRepo);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Configured JEditorPane skips JCEF startup initialization")
    void shouldInitialize_whenJEditorPaneConfigured_returnsFalse() throws Exception {
        SettingsRepo settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.CHAT_WEB_VIEW_ENGINE, ChatWebViewEngine.JEDITOR_PANE.settingValue());
        var subject = decider(false, false, false);

        boolean result = subject.shouldInitialize(settingsRepo);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Invalid engine setting skips JCEF startup initialization")
    void shouldInitialize_whenInvalidEngineConfigured_returnsFalse() throws Exception {
        SettingsRepo settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.CHAT_WEB_VIEW_ENGINE, "invalid");
        var subject = decider(false, false, false);

        boolean result = subject.shouldInitialize(settingsRepo);

        assertThat(result).isFalse();
    }

    private JcefStartupInitializationDecider decider(boolean macOs, boolean windows, boolean nativeWebViewAvailable) {
        return new JcefStartupInitializationDecider(
                () -> macOs,
                () -> windows,
                () -> nativeWebViewAvailable
        );
    }

    private SettingsRepo settingsRepo() {
        return new SettingsRepo(tempDir.resolve("settings.properties"));
    }
}
