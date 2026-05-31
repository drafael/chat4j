package com.github.drafael.chat4j.chat.message;

import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ChatWebViewRuntimeStatusResolverTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("Default settings use JEditorPane outside macOS and Windows")
    void resolve_whenNoSettingConfiguredOutsideMacOsAndWindows_usesJEditorPane() {
        var subject = resolver(false, false, available());

        ChatWebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(ChatWebViewEngine.JEDITOR_PANE);
        assertThat(result.activeEngine()).isEqualTo(ChatWebViewEngine.JEDITOR_PANE);
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("Default settings use SwingWebView on macOS")
    void resolve_whenNoSettingConfiguredOnMacOs_usesSwingWebView() {
        var subject = resolver(true, false, available());

        ChatWebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(ChatWebViewEngine.SWING_WEBVIEW);
        assertThat(result.activeEngine()).isEqualTo(ChatWebViewEngine.SWING_WEBVIEW);
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("Default settings use SwingWebView on Windows only when the startup capability check passes")
    void resolve_whenNoSettingConfiguredOnWindowsAndSwingWebViewAvailable_usesSwingWebView() {
        var subject = resolver(false, true, available());

        ChatWebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(ChatWebViewEngine.SWING_WEBVIEW);
        assertThat(result.activeEngine()).isEqualTo(ChatWebViewEngine.SWING_WEBVIEW);
        assertThat(result.swingWebViewAvailable()).isTrue();
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("Default settings keep JEditorPane on Windows when the startup capability check fails")
    void resolve_whenNoSettingConfiguredOnWindowsAndSwingWebViewUnavailable_usesJEditorPaneWithoutFallback() {
        var subject = resolver(false, true, unavailable());

        ChatWebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(ChatWebViewEngine.JEDITOR_PANE);
        assertThat(result.activeEngine()).isEqualTo(ChatWebViewEngine.JEDITOR_PANE);
        assertThat(result.swingWebViewAvailable()).isFalse();
        assertThat(result.fallbackReason()).isEmpty();
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("Invalid configured engine falls back to JEditorPane")
    void resolve_whenInvalidEngineConfigured_usesJEditorPane() throws Exception {
        SettingsRepo settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.CHAT_WEB_VIEW_ENGINE, "invalid");
        var subject = resolver(settingsRepo, true, false, available());

        ChatWebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(ChatWebViewEngine.JEDITOR_PANE);
        assertThat(result.activeEngine()).isEqualTo(ChatWebViewEngine.JEDITOR_PANE);
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("SwingWebView setting uses SwingWebView when dependency is available")
    void resolve_whenSwingWebViewConfigured_usesSwingWebViewWhenAvailable() throws Exception {
        SettingsRepo settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.CHAT_WEB_VIEW_ENGINE, ChatWebViewEngine.SWING_WEBVIEW.settingValue());
        var subject = resolver(settingsRepo, false, false, available());

        ChatWebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(ChatWebViewEngine.SWING_WEBVIEW);
        assertThat(result.activeEngine()).isEqualTo(ChatWebViewEngine.SWING_WEBVIEW);
        assertThat(result.swingWebViewAvailable()).isTrue();
        assertThat(result.swingWebViewMode()).isNotBlank();
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("SwingWebView setting falls back to JEditorPane when dependency is unavailable")
    void resolve_whenSwingWebViewConfiguredAndUnavailable_usesJEditorPaneFallback() throws Exception {
        SettingsRepo settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.CHAT_WEB_VIEW_ENGINE, ChatWebViewEngine.SWING_WEBVIEW.settingValue());
        var subject = resolver(settingsRepo, false, true, unavailable());

        ChatWebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(ChatWebViewEngine.SWING_WEBVIEW);
        assertThat(result.activeEngine()).isEqualTo(ChatWebViewEngine.JEDITOR_PANE);
        assertThat(result.swingWebViewAvailable()).isFalse();
        assertThat(result.fallbackReason()).isEqualTo("missing runtime");
        assertThat(result.hasFallback()).isTrue();
    }

    private ChatWebViewRuntimeStatusResolver resolver(
            boolean macOs,
            boolean windows,
            ChatWebViewRuntimeStatusResolver.SwingWebViewAvailability availability
    ) {
        return resolver(settingsRepo(), macOs, windows, availability);
    }

    private ChatWebViewRuntimeStatusResolver resolver(
            SettingsRepo settingsRepo,
            boolean macOs,
            boolean windows,
            ChatWebViewRuntimeStatusResolver.SwingWebViewAvailability availability
    ) {
        return new ChatWebViewRuntimeStatusResolver(settingsRepo, () -> macOs, () -> windows, () -> availability);
    }

    private ChatWebViewRuntimeStatusResolver.SwingWebViewAvailability available() {
        return new ChatWebViewRuntimeStatusResolver.SwingWebViewAvailability(true, "HEAVYWEIGHT", "");
    }

    private ChatWebViewRuntimeStatusResolver.SwingWebViewAvailability unavailable() {
        return new ChatWebViewRuntimeStatusResolver.SwingWebViewAvailability(false, "Unavailable", "missing runtime");
    }

    private SettingsRepo settingsRepo() {
        return new SettingsRepo(tempDir.resolve("settings.properties"));
    }
}
