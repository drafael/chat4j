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
    @DisplayName("Default settings use JEditorPane as the active engine")
    void resolve_whenNoSettingConfigured_usesJEditorPane() {
        var subject = new ChatWebViewRuntimeStatusResolver(settingsRepo());

        ChatWebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(ChatWebViewEngine.JEDITOR_PANE);
        assertThat(result.activeEngine()).isEqualTo(ChatWebViewEngine.JEDITOR_PANE);
        assertThat(result.hasFallback()).isFalse();
    }

    @Test
    @DisplayName("Invalid configured engine falls back to JEditorPane")
    void resolve_whenInvalidEngineConfigured_usesJEditorPane() throws Exception {
        SettingsRepo settingsRepo = settingsRepo();
        settingsRepo.put(SettingsKeys.CHAT_WEB_VIEW_ENGINE, "invalid");
        var subject = new ChatWebViewRuntimeStatusResolver(settingsRepo);

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
        var subject = new ChatWebViewRuntimeStatusResolver(settingsRepo);

        ChatWebViewRuntimeStatus result = subject.resolve();

        assertThat(result.configuredEngine()).isEqualTo(ChatWebViewEngine.SWING_WEBVIEW);
        assertThat(result.activeEngine()).isEqualTo(ChatWebViewEngine.SWING_WEBVIEW);
        assertThat(result.swingWebViewAvailable()).isTrue();
        assertThat(result.swingWebViewMode()).isNotBlank();
        assertThat(result.hasFallback()).isFalse();
    }

    private SettingsRepo settingsRepo() {
        return new SettingsRepo(tempDir.resolve("settings.properties"));
    }
}
