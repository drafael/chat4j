package com.github.drafael.chat4j.chat.message;

import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;

public enum ChatWebViewEngine {

    JEDITOR_PANE("jeditor-pane", "JEditorPane"),
    SWING_WEBVIEW("swing-webview", "SwingWebView");

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
        if (StringUtils.isBlank(value)) {
            return JEDITOR_PANE;
        }

        String normalized = value.trim();
        return Arrays.stream(values())
                .filter(engine -> engine.settingValue.equalsIgnoreCase(normalized) || engine.name().equalsIgnoreCase(normalized))
                .findFirst()
                .orElse(JEDITOR_PANE);
    }
}
