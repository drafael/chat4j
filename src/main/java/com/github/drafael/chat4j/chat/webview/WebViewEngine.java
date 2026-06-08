package com.github.drafael.chat4j.chat.webview;

import com.formdev.flatlaf.util.SystemInfo;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.Arrays;
import java.util.Optional;

public enum WebViewEngine {

    JEDITOR_PANE("jeditor-pane", "Swing HTML Renderer"),
    SYSTEM("system", "System WebView"),
    JCEF("jcef", "Chromium Embedded Framework");

    private final String settingValue;
    private final String displayName;

    WebViewEngine(String settingValue, String displayName) {
        this.settingValue = settingValue;
        this.displayName = displayName;
    }

    public String settingValue() {
        return settingValue;
    }

    public String displayName() {
        return displayName;
    }

    public static WebViewEngine fromSettingValue(String value) {
        return fromSettingValue(value, defaultEngine());
    }

    public static WebViewEngine fromSettingValue(String value, WebViewEngine defaultEngine) {
        return findBySettingValue(value).orElse(defaultEngine == null ? defaultEngine() : defaultEngine);
    }

    public static Optional<WebViewEngine> findBySettingValue(String value) {
        String normalized = StringUtils.trimToEmpty(value);
        return Arrays.stream(values())
                .filter(engine -> Strings.CI.equals(engine.settingValue, normalized) || Strings.CI.equals(engine.name(), normalized))
                .findFirst();
    }

    static WebViewEngine defaultEngine() {
        return SystemInfo.isMacOS || SystemInfo.isWindows ? SYSTEM : JCEF;
    }
}
