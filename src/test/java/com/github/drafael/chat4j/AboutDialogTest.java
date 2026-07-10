package com.github.drafael.chat4j;

import com.github.drafael.chat4j.chat.webview.WebViewEngine;
import com.github.drafael.chat4j.chat.webview.WebViewRuntimeStatus;
import com.github.drafael.chat4j.chat.webview.WebViewSettings;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class AboutDialogTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Selected WebView engine falls back to status configured engine when repository is absent")
    void selectedWebViewEngine_whenSettingsRepoIsNull_returnsStatusConfiguredEngine() {
        WebViewRuntimeStatus status = status(WebViewEngine.JCEF);

        WebViewEngine selected = AboutDialog.selectedWebViewEngine(null, status);

        assertThat(selected).isEqualTo(WebViewEngine.JCEF);
    }

    @Test
    @DisplayName("Selected WebView engine falls back to status configured engine when repository read fails")
    void selectedWebViewEngine_whenSettingsRepoFails_returnsStatusConfiguredEngine() {
        WebViewRuntimeStatus status = status(WebViewEngine.JCEF);

        WebViewEngine selected = AboutDialog.selectedWebViewEngine(new ThrowingSettingsRepo(), status);

        assertThat(selected).isEqualTo(WebViewEngine.JCEF);
    }

    @Test
    @DisplayName("Selected WebView engine uses default engine fallback for invalid stored values")
    void selectedWebViewEngine_whenStoredValueIsInvalid_usesWebViewEngineDefaultFallback() {
        SettingsRepository settingsRepo = settingsRepo("about-webview-invalid");
        settingsRepo.put(WebViewSettings.ENGINE_KEY, "obsolete-webview");
        WebViewRuntimeStatus status = status(WebViewEngine.JCEF);

        WebViewEngine selected = AboutDialog.selectedWebViewEngine(settingsRepo, status);

        assertThat(selected).isEqualTo(WebViewEngine.fromSettingValue("obsolete-webview"));
    }

    private WebViewRuntimeStatus status(WebViewEngine configuredEngine) {
        return new WebViewRuntimeStatus(
                configuredEngine,
                configuredEngine,
                true,
                "HEAVYWEIGHT",
                true,
                "Windowed/native",
                ""
        );
    }

    private SettingsRepository settingsRepo(String name) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(name)));
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private ThrowingSettingsRepo() {
            super(Path.of("unused-about-dialog.properties"));
        }

        @Override
        public String get(String key, String defaultValue) {
            throw new IllegalStateException("forced failure");
        }
    }
}
