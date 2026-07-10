package com.github.drafael.chat4j.chat.webview;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class WebViewSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("WebView settings owns the backward-compatible engine key")
    void engineKey_whenRead_matchesPersistedKey() {
        assertThat(WebViewSettings.ENGINE_KEY).isEqualTo("chat4j.chat.webView.engine");
    }

    @Test
    @DisplayName("WebView settings reads raw engine values and resolves with caller fallback")
    void readMethods_whenConfigured_returnRawAndResolvedValues() {
        SettingsRepository settingsRepo = settingsRepo("webview-settings-configured");
        settingsRepo.put(WebViewSettings.ENGINE_KEY, "obsolete-webview");
        var subject = new WebViewSettings(settingsRepo);

        assertThat(subject.readEngineValue(WebViewEngine.SYSTEM.settingValue())).isEqualTo("obsolete-webview");
        assertThat(subject.resolveEngine(WebViewEngine.JCEF)).isEqualTo(WebViewEngine.JCEF);
    }

    @Test
    @DisplayName("WebView settings read methods fall back when repository access fails")
    void readMethods_whenRepositoryFails_returnFallbacks() {
        var subject = new WebViewSettings(new ThrowingSettingsRepo());

        assertThat(subject.readEngineValue(WebViewEngine.SYSTEM.settingValue())).isEqualTo(WebViewEngine.SYSTEM.settingValue());
        assertThat(subject.resolveEngine(WebViewEngine.JCEF)).isEqualTo(WebViewEngine.JCEF);
    }

    private SettingsRepository settingsRepo(String name) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(name)));
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private ThrowingSettingsRepo() {
            super(Path.of("unused-webview-settings.properties"));
        }

        @Override
        public String get(String key, String defaultValue) {
            throw new IllegalStateException("forced failure");
        }
    }
}
