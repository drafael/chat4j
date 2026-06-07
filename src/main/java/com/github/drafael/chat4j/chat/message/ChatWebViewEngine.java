package com.github.drafael.chat4j.chat.message;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.Arrays;

public enum ChatWebViewEngine {

    JEDITOR_PANE("jeditor-pane", "Swing HTML Renderer"),
    NATIVE_WEBVIEW("native-webview", "Native OS WebView"),
    JCEF("jcef", "Chromium Embedded Framework");

    private final String settingValue;
    private final String displayName;

    ChatWebViewEngine(String settingValue, String displayName) {
        this.settingValue = settingValue;
        this.displayName = displayName;
    }

    public String settingValue() {
        return settingValue;
    }

    public String displayName() {
        return displayName;
    }

    public static ChatWebViewEngine fromSettingValue(String value) {
        String normalized = StringUtils.trimToEmpty(value);
        return Arrays.stream(values())
                .filter(engine -> Strings.CI.equals(engine.settingValue, normalized) || Strings.CI.equals(engine.name(), normalized))
                .findFirst()
                .orElse(JEDITOR_PANE);
    }
}
