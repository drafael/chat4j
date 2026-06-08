package com.github.drafael.chat4j.chat.webview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebViewEngineTest {

    @Test
    @DisplayName("Blank setting resolves to platform default")
    void fromSettingValue_whenBlank_returnsPlatformDefault() {
        assertThat(WebViewEngine.fromSettingValue(" ")).isEqualTo(WebViewEngine.defaultEngine());
    }

    @Test
    @DisplayName("Setting value resolves to System WebView")
    void fromSettingValue_whenSystemWebViewSettingValue_returnsSystemWebView() {
        assertThat(WebViewEngine.fromSettingValue("system")).isEqualTo(WebViewEngine.SYSTEM);
    }

    @Test
    @DisplayName("Enum name resolves to System WebView")
    void fromSettingValue_whenSystemWebViewEnumName_returnsSystemWebView() {
        assertThat(WebViewEngine.fromSettingValue("SYSTEM")).isEqualTo(WebViewEngine.SYSTEM);
    }

    @Test
    @DisplayName("Obsolete setting resolves to platform default")
    void fromSettingValue_whenObsoleteSettingValue_returnsPlatformDefault() {
        assertThat(WebViewEngine.fromSettingValue("obsolete-webview")).isEqualTo(WebViewEngine.defaultEngine());
    }

    @Test
    @DisplayName("Obsolete enum name resolves to platform default")
    void fromSettingValue_whenObsoleteEnumName_returnsPlatformDefault() {
        assertThat(WebViewEngine.fromSettingValue("OBSOLETE_WEBVIEW")).isEqualTo(WebViewEngine.defaultEngine());
    }

    @Test
    @DisplayName("Setting value resolves to JCEF")
    void fromSettingValue_whenJcefSettingValue_returnsJcef() {
        assertThat(WebViewEngine.fromSettingValue("jcef")).isEqualTo(WebViewEngine.JCEF);
    }

    @Test
    @DisplayName("Enum name resolves to JCEF")
    void fromSettingValue_whenJcefEnumName_returnsJcef() {
        assertThat(WebViewEngine.fromSettingValue("JCEF")).isEqualTo(WebViewEngine.JCEF);
    }

    @Test
    @DisplayName("Unknown setting resolves to platform default")
    void fromSettingValue_whenUnknown_returnsPlatformDefault() {
        assertThat(WebViewEngine.fromSettingValue("unknown")).isEqualTo(WebViewEngine.defaultEngine());
    }

    @Test
    @DisplayName("Unknown setting resolves to supplied default")
    void fromSettingValue_whenUnknownAndDefaultProvided_returnsProvidedDefault() {
        assertThat(WebViewEngine.fromSettingValue("unknown", WebViewEngine.SYSTEM)).isEqualTo(WebViewEngine.SYSTEM);
    }
}
