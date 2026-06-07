package com.github.drafael.chat4j.chat.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatWebViewEngineTest {

    @Test
    @DisplayName("Blank setting resolves to JEditorPane")
    void fromSettingValue_whenBlank_returnsJEditorPane() {
        assertThat(ChatWebViewEngine.fromSettingValue(" ")).isEqualTo(ChatWebViewEngine.JEDITOR_PANE);
    }

    @Test
    @DisplayName("Setting value resolves to SwingWebView")
    void fromSettingValue_whenSwingWebViewSettingValue_returnsSwingWebView() {
        assertThat(ChatWebViewEngine.fromSettingValue("native-webview")).isEqualTo(ChatWebViewEngine.NATIVE_WEBVIEW);
    }

    @Test
    @DisplayName("Enum name resolves to SwingWebView")
    void fromSettingValue_whenEnumName_returnsSwingWebView() {
        assertThat(ChatWebViewEngine.fromSettingValue("NATIVE_WEBVIEW")).isEqualTo(ChatWebViewEngine.NATIVE_WEBVIEW);
    }

    @Test
    @DisplayName("Setting value resolves to JCEF")
    void fromSettingValue_whenJcefSettingValue_returnsJcef() {
        assertThat(ChatWebViewEngine.fromSettingValue("jcef")).isEqualTo(ChatWebViewEngine.JCEF);
    }

    @Test
    @DisplayName("Enum name resolves to JCEF")
    void fromSettingValue_whenJcefEnumName_returnsJcef() {
        assertThat(ChatWebViewEngine.fromSettingValue("JCEF")).isEqualTo(ChatWebViewEngine.JCEF);
    }

    @Test
    @DisplayName("Unknown setting falls back to JEditorPane")
    void fromSettingValue_whenUnknown_returnsJEditorPane() {
        assertThat(ChatWebViewEngine.fromSettingValue("unknown")).isEqualTo(ChatWebViewEngine.JEDITOR_PANE);
    }
}
