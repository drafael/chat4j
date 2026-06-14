package com.github.drafael.chat4j.chat.webview;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class WebViewRuntimeStatusResolverTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("Default settings use System WebView on macOS when available")
    void resolve_whenNoSettingConfiguredOnMacOsAndSystemWebViewAvailable_usesSystemWebView() {
        var subject = resolver(true, false, nativeAvailable(), jcefAvailable());

        WebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(WebViewEngine.SYSTEM);
        assertThat(result.activeEngine()).isEqualTo(WebViewEngine.SYSTEM);
        assertThat(result.jcefMode()).isEqualTo("Not checked");
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("Default settings use JCEF on macOS when System WebView is unavailable")
    void resolve_whenNoSettingConfiguredOnMacOsAndSystemWebViewUnavailable_usesJcefFallback() {
        var subject = resolver(true, false, nativeUnavailable(), jcefAvailable());

        WebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(WebViewEngine.SYSTEM);
        assertThat(result.activeEngine()).isEqualTo(WebViewEngine.JCEF);
        assertThat(result.swingWebViewAvailable()).isFalse();
        assertThat(result.jcefAvailable()).isTrue();
        assertThat(result.fallbackReason()).contains("System WebView unavailable: missing runtime");
        assertThat(result.hasFallback()).isTrue();
    }

    @Test
    @DisplayName("Default settings use JEditorPane on macOS when System WebView and JCEF are unavailable")
    void resolve_whenNoSettingConfiguredOnMacOsAndSystemWebViewAndJcefUnavailable_usesJEditorPaneFallback() {
        var subject = resolver(true, false, nativeUnavailable(), jcefUnavailable());

        WebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(WebViewEngine.SYSTEM);
        assertThat(result.activeEngine()).isEqualTo(WebViewEngine.JEDITOR_PANE);
        assertThat(result.fallbackReason())
                .contains("System WebView unavailable: missing runtime")
                .contains("Chromium Embedded Framework unavailable: native bundle missing");
        assertThat(result.hasFallback()).isTrue();
    }

    @Test
    @DisplayName("Default settings use System WebView on Windows when available")
    void resolve_whenNoSettingConfiguredOnWindowsAndSystemWebViewAvailable_usesSystemWebView() {
        var subject = resolver(false, true, nativeAvailable(), jcefAvailable());

        WebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(WebViewEngine.SYSTEM);
        assertThat(result.activeEngine()).isEqualTo(WebViewEngine.SYSTEM);
        assertThat(result.swingWebViewAvailable()).isTrue();
        assertThat(result.jcefMode()).isEqualTo("Not checked");
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("Default settings use JCEF outside macOS and Windows when available")
    void resolve_whenNoSettingConfiguredOutsideMacOsAndWindowsAndJcefAvailable_usesJcef() {
        var subject = resolver(false, false, nativeAvailable(), jcefAvailable());

        WebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(WebViewEngine.JCEF);
        assertThat(result.activeEngine()).isEqualTo(WebViewEngine.JCEF);
        assertThat(result.jcefAvailable()).isTrue();
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("Default settings use JEditorPane outside macOS and Windows when JCEF is unavailable")
    void resolve_whenNoSettingConfiguredOutsideMacOsAndWindowsAndJcefUnavailable_usesJEditorPaneFallback() {
        var subject = resolver(false, false, nativeAvailable(), jcefUnavailable());

        WebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(WebViewEngine.JCEF);
        assertThat(result.activeEngine()).isEqualTo(WebViewEngine.JEDITOR_PANE);
        assertThat(result.fallbackReason()).contains("Chromium Embedded Framework unavailable: native bundle missing");
        assertThat(result.hasFallback()).isTrue();
    }

    @Test
    @DisplayName("Invalid configured engine uses the macOS platform default")
    void resolve_whenInvalidEngineConfiguredOnMacOs_usesPlatformDefault() throws Exception {
        SettingsRepository settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.WEBVIEW_ENGINE, "invalid");
        var subject = resolver(settingsRepo, true, false, nativeAvailable(), jcefAvailable());

        WebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(WebViewEngine.SYSTEM);
        assertThat(result.activeEngine()).isEqualTo(WebViewEngine.SYSTEM);
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("Obsolete engine setting uses the macOS platform default")
    void resolve_whenObsoleteEngineConfiguredOnMacOs_usesPlatformDefault() throws Exception {
        SettingsRepository settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.WEBVIEW_ENGINE, "obsolete-webview");
        var subject = resolver(settingsRepo, true, false, nativeAvailable(), jcefAvailable());

        WebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(WebViewEngine.SYSTEM);
        assertThat(result.activeEngine()).isEqualTo(WebViewEngine.SYSTEM);
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("Invalid configured engine uses the non-system platform default")
    void resolve_whenInvalidEngineConfiguredOutsideMacOsAndWindows_usesPlatformDefault() throws Exception {
        SettingsRepository settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.WEBVIEW_ENGINE, "invalid");
        var subject = resolver(settingsRepo, false, false, nativeAvailable(), jcefAvailable());

        WebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(WebViewEngine.JCEF);
        assertThat(result.activeEngine()).isEqualTo(WebViewEngine.JCEF);
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("System WebView setting uses System WebView when available")
    void resolve_whenSystemWebViewConfigured_usesSystemWebViewWhenAvailable() throws Exception {
        SettingsRepository settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.WEBVIEW_ENGINE, WebViewEngine.SYSTEM.settingValue());
        var subject = resolver(settingsRepo, false, false, nativeAvailable(), jcefAvailable());

        WebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(WebViewEngine.SYSTEM);
        assertThat(result.activeEngine()).isEqualTo(WebViewEngine.SYSTEM);
        assertThat(result.swingWebViewAvailable()).isTrue();
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("System WebView setting falls back through platform chain when unavailable")
    void resolve_whenSystemWebViewConfiguredAndUnavailable_usesPlatformFallbackChain() throws Exception {
        SettingsRepository settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.WEBVIEW_ENGINE, WebViewEngine.SYSTEM.settingValue());
        var subject = resolver(settingsRepo, true, false, nativeUnavailable(), jcefAvailable());

        WebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(WebViewEngine.SYSTEM);
        assertThat(result.activeEngine()).isEqualTo(WebViewEngine.JCEF);
        assertThat(result.fallbackReason()).contains("System WebView unavailable: missing runtime");
        assertThat(result.hasFallback()).isTrue();
    }

    @Test
    @DisplayName("JCEF setting uses JCEF when runtime is available")
    void resolve_whenJcefConfiguredAndAvailable_usesJcef() throws Exception {
        SettingsRepository settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.WEBVIEW_ENGINE, WebViewEngine.JCEF.settingValue());
        var subject = resolver(settingsRepo, true, false, nativeAvailable(), jcefAvailable());

        WebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(WebViewEngine.JCEF);
        assertThat(result.activeEngine()).isEqualTo(WebViewEngine.JCEF);
        assertThat(result.jcefAvailable()).isTrue();
        assertThat(result.jcefMode()).isEqualTo("Windowed/native");
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("JCEF setting falls back to System WebView on macOS when JCEF is unavailable")
    void resolve_whenJcefConfiguredAndUnavailableOnMacOs_usesPlatformFallbackChain() throws Exception {
        SettingsRepository settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.WEBVIEW_ENGINE, WebViewEngine.JCEF.settingValue());
        var subject = resolver(settingsRepo, true, false, nativeAvailable(), jcefUnavailable());

        WebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(WebViewEngine.JCEF);
        assertThat(result.activeEngine()).isEqualTo(WebViewEngine.SYSTEM);
        assertThat(result.fallbackReason()).contains("Chromium Embedded Framework unavailable: native bundle missing");
        assertThat(result.hasFallback()).isTrue();
    }

    @Test
    @DisplayName("JCEF setting falls back to JEditorPane on Linux when JCEF is unavailable")
    void resolve_whenJcefConfiguredAndUnavailableOutsideMacOsAndWindows_usesJEditorPaneFallback() throws Exception {
        SettingsRepository settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.WEBVIEW_ENGINE, WebViewEngine.JCEF.settingValue());
        var subject = resolver(settingsRepo, false, false, nativeAvailable(), jcefUnavailable());

        WebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(WebViewEngine.JCEF);
        assertThat(result.activeEngine()).isEqualTo(WebViewEngine.JEDITOR_PANE);
        assertThat(result.hasFallback()).isTrue();
    }

    private WebViewRuntimeStatusResolver resolver(
            boolean macOs,
            boolean windows,
            WebViewRuntimeStatusResolver.SwingWebViewAvailability availability,
            WebViewRuntimeStatusResolver.JcefAvailability jcefAvailability
    ) {
        return resolver(settingsRepo(), macOs, windows, availability, jcefAvailability);
    }

    private WebViewRuntimeStatusResolver resolver(
            SettingsRepository settingsRepo,
            boolean macOs,
            boolean windows,
            WebViewRuntimeStatusResolver.SwingWebViewAvailability availability,
            WebViewRuntimeStatusResolver.JcefAvailability jcefAvailability
    ) {
        return new WebViewRuntimeStatusResolver(
                settingsRepo,
                () -> macOs,
                () -> windows,
                () -> availability,
                () -> jcefAvailability
        );
    }

    private WebViewRuntimeStatusResolver.SwingWebViewAvailability nativeAvailable() {
        return new WebViewRuntimeStatusResolver.SwingWebViewAvailability(true, "HEAVYWEIGHT", "");
    }

    private WebViewRuntimeStatusResolver.SwingWebViewAvailability nativeUnavailable() {
        return new WebViewRuntimeStatusResolver.SwingWebViewAvailability(false, "Unavailable", "missing runtime");
    }

    private WebViewRuntimeStatusResolver.JcefAvailability jcefAvailable() {
        return new WebViewRuntimeStatusResolver.JcefAvailability(true, "Windowed/native", "");
    }

    private WebViewRuntimeStatusResolver.JcefAvailability jcefUnavailable() {
        return new WebViewRuntimeStatusResolver.JcefAvailability(false, "Unavailable", "native bundle missing");
    }

    private SettingsRepository settingsRepo() {
        return new SettingsRepository(tempDir.resolve("settings.properties"));
    }
}
