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
    @DisplayName("Default settings use Native WebView on macOS when available")
    void resolve_whenNoSettingConfiguredOnMacOsAndNativeWebViewAvailable_usesNativeWebView() {
        var subject = resolver(true, false, nativeAvailable(), jcefAvailable());

        ChatWebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(ChatWebViewEngine.NATIVE_WEBVIEW);
        assertThat(result.activeEngine()).isEqualTo(ChatWebViewEngine.NATIVE_WEBVIEW);
        assertThat(result.jcefMode()).isEqualTo("Not checked");
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("Default settings use JCEF on macOS when Native WebView is unavailable")
    void resolve_whenNoSettingConfiguredOnMacOsAndNativeWebViewUnavailable_usesJcefFallback() {
        var subject = resolver(true, false, nativeUnavailable(), jcefAvailable());

        ChatWebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(ChatWebViewEngine.NATIVE_WEBVIEW);
        assertThat(result.activeEngine()).isEqualTo(ChatWebViewEngine.JCEF);
        assertThat(result.swingWebViewAvailable()).isFalse();
        assertThat(result.jcefAvailable()).isTrue();
        assertThat(result.fallbackReason()).contains("Native OS WebView unavailable: missing runtime");
        assertThat(result.hasFallback()).isTrue();
    }

    @Test
    @DisplayName("Default settings use JEditorPane on macOS when Native WebView and JCEF are unavailable")
    void resolve_whenNoSettingConfiguredOnMacOsAndNativeWebViewAndJcefUnavailable_usesJEditorPaneFallback() {
        var subject = resolver(true, false, nativeUnavailable(), jcefUnavailable());

        ChatWebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(ChatWebViewEngine.NATIVE_WEBVIEW);
        assertThat(result.activeEngine()).isEqualTo(ChatWebViewEngine.JEDITOR_PANE);
        assertThat(result.fallbackReason())
                .contains("Native OS WebView unavailable: missing runtime")
                .contains("Chromium Embedded Framework unavailable: native bundle missing");
        assertThat(result.hasFallback()).isTrue();
    }

    @Test
    @DisplayName("Default settings use Native WebView on Windows when available")
    void resolve_whenNoSettingConfiguredOnWindowsAndNativeWebViewAvailable_usesNativeWebView() {
        var subject = resolver(false, true, nativeAvailable(), jcefAvailable());

        ChatWebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(ChatWebViewEngine.NATIVE_WEBVIEW);
        assertThat(result.activeEngine()).isEqualTo(ChatWebViewEngine.NATIVE_WEBVIEW);
        assertThat(result.swingWebViewAvailable()).isTrue();
        assertThat(result.jcefMode()).isEqualTo("Not checked");
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("Default settings use JCEF outside macOS and Windows when available")
    void resolve_whenNoSettingConfiguredOutsideMacOsAndWindowsAndJcefAvailable_usesJcef() {
        var subject = resolver(false, false, nativeAvailable(), jcefAvailable());

        ChatWebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(ChatWebViewEngine.JCEF);
        assertThat(result.activeEngine()).isEqualTo(ChatWebViewEngine.JCEF);
        assertThat(result.jcefAvailable()).isTrue();
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("Default settings use JEditorPane outside macOS and Windows when JCEF is unavailable")
    void resolve_whenNoSettingConfiguredOutsideMacOsAndWindowsAndJcefUnavailable_usesJEditorPaneFallback() {
        var subject = resolver(false, false, nativeAvailable(), jcefUnavailable());

        ChatWebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(ChatWebViewEngine.JCEF);
        assertThat(result.activeEngine()).isEqualTo(ChatWebViewEngine.JEDITOR_PANE);
        assertThat(result.fallbackReason()).contains("Chromium Embedded Framework unavailable: native bundle missing");
        assertThat(result.hasFallback()).isTrue();
    }

    @Test
    @DisplayName("Invalid configured engine falls back to JEditorPane")
    void resolve_whenInvalidEngineConfigured_usesJEditorPane() throws Exception {
        SettingsRepo settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.CHAT_WEB_VIEW_ENGINE, "invalid");
        var subject = resolver(settingsRepo, true, false, nativeAvailable(), jcefAvailable());

        ChatWebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(ChatWebViewEngine.JEDITOR_PANE);
        assertThat(result.activeEngine()).isEqualTo(ChatWebViewEngine.JEDITOR_PANE);
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("Native WebView setting uses Native WebView when available")
    void resolve_whenNativeWebViewConfigured_usesNativeWebViewWhenAvailable() throws Exception {
        SettingsRepo settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.CHAT_WEB_VIEW_ENGINE, ChatWebViewEngine.NATIVE_WEBVIEW.settingValue());
        var subject = resolver(settingsRepo, false, false, nativeAvailable(), jcefAvailable());

        ChatWebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(ChatWebViewEngine.NATIVE_WEBVIEW);
        assertThat(result.activeEngine()).isEqualTo(ChatWebViewEngine.NATIVE_WEBVIEW);
        assertThat(result.swingWebViewAvailable()).isTrue();
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("Native WebView setting falls back through platform chain when unavailable")
    void resolve_whenNativeWebViewConfiguredAndUnavailable_usesPlatformFallbackChain() throws Exception {
        SettingsRepo settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.CHAT_WEB_VIEW_ENGINE, ChatWebViewEngine.NATIVE_WEBVIEW.settingValue());
        var subject = resolver(settingsRepo, true, false, nativeUnavailable(), jcefAvailable());

        ChatWebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(ChatWebViewEngine.NATIVE_WEBVIEW);
        assertThat(result.activeEngine()).isEqualTo(ChatWebViewEngine.JCEF);
        assertThat(result.fallbackReason()).contains("Native OS WebView unavailable: missing runtime");
        assertThat(result.hasFallback()).isTrue();
    }

    @Test
    @DisplayName("JCEF setting uses JCEF when runtime is available")
    void resolve_whenJcefConfiguredAndAvailable_usesJcef() throws Exception {
        SettingsRepo settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.CHAT_WEB_VIEW_ENGINE, ChatWebViewEngine.JCEF.settingValue());
        var subject = resolver(settingsRepo, true, false, nativeAvailable(), jcefAvailable());

        ChatWebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(ChatWebViewEngine.JCEF);
        assertThat(result.activeEngine()).isEqualTo(ChatWebViewEngine.JCEF);
        assertThat(result.jcefAvailable()).isTrue();
        assertThat(result.jcefMode()).isEqualTo("Windowed/native");
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("JCEF setting falls back to Native WebView on macOS when JCEF is unavailable")
    void resolve_whenJcefConfiguredAndUnavailableOnMacOs_usesPlatformFallbackChain() throws Exception {
        SettingsRepo settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.CHAT_WEB_VIEW_ENGINE, ChatWebViewEngine.JCEF.settingValue());
        var subject = resolver(settingsRepo, true, false, nativeAvailable(), jcefUnavailable());

        ChatWebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(ChatWebViewEngine.JCEF);
        assertThat(result.activeEngine()).isEqualTo(ChatWebViewEngine.NATIVE_WEBVIEW);
        assertThat(result.fallbackReason()).contains("Chromium Embedded Framework unavailable: native bundle missing");
        assertThat(result.hasFallback()).isTrue();
    }

    @Test
    @DisplayName("JCEF setting falls back to JEditorPane on Linux when JCEF is unavailable")
    void resolve_whenJcefConfiguredAndUnavailableOutsideMacOsAndWindows_usesJEditorPaneFallback() throws Exception {
        SettingsRepo settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.CHAT_WEB_VIEW_ENGINE, ChatWebViewEngine.JCEF.settingValue());
        var subject = resolver(settingsRepo, false, false, nativeAvailable(), jcefUnavailable());

        ChatWebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(ChatWebViewEngine.JCEF);
        assertThat(result.activeEngine()).isEqualTo(ChatWebViewEngine.JEDITOR_PANE);
        assertThat(result.hasFallback()).isTrue();
    }

    private ChatWebViewRuntimeStatusResolver resolver(
            boolean macOs,
            boolean windows,
            ChatWebViewRuntimeStatusResolver.SwingWebViewAvailability availability,
            ChatWebViewRuntimeStatusResolver.JcefAvailability jcefAvailability
    ) {
        return resolver(settingsRepo(), macOs, windows, availability, jcefAvailability);
    }

    private ChatWebViewRuntimeStatusResolver resolver(
            SettingsRepo settingsRepo,
            boolean macOs,
            boolean windows,
            ChatWebViewRuntimeStatusResolver.SwingWebViewAvailability availability,
            ChatWebViewRuntimeStatusResolver.JcefAvailability jcefAvailability
    ) {
        return new ChatWebViewRuntimeStatusResolver(
                settingsRepo,
                () -> macOs,
                () -> windows,
                () -> availability,
                () -> jcefAvailability
        );
    }

    private ChatWebViewRuntimeStatusResolver.SwingWebViewAvailability nativeAvailable() {
        return new ChatWebViewRuntimeStatusResolver.SwingWebViewAvailability(true, "HEAVYWEIGHT", "");
    }

    private ChatWebViewRuntimeStatusResolver.SwingWebViewAvailability nativeUnavailable() {
        return new ChatWebViewRuntimeStatusResolver.SwingWebViewAvailability(false, "Unavailable", "missing runtime");
    }

    private ChatWebViewRuntimeStatusResolver.JcefAvailability jcefAvailable() {
        return new ChatWebViewRuntimeStatusResolver.JcefAvailability(true, "Windowed/native", "");
    }

    private ChatWebViewRuntimeStatusResolver.JcefAvailability jcefUnavailable() {
        return new ChatWebViewRuntimeStatusResolver.JcefAvailability(false, "Unavailable", "native bundle missing");
    }

    private SettingsRepo settingsRepo() {
        return new SettingsRepo(tempDir.resolve("settings.properties"));
    }
}
