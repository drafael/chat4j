package com.github.drafael.chat4j.chat;

import org.apache.commons.lang3.StringUtils;

public enum RenderMode {
    PREVIEW("preview", "Preview"),
    MARKDOWN("markdown", "Markdown");

    private final String settingValue;
    private final String displayName;

    RenderMode(String settingValue, String displayName) {
        this.settingValue = settingValue;
        this.displayName = displayName;
    }

    public String settingValue() {
        return settingValue;
    }

    public String displayName() {
        return displayName;
    }

    public static RenderMode fromSettingValue(String value) {
        if (StringUtils.isBlank(value)) {
            return PREVIEW;
        }

        String normalized = value.trim();
        for (RenderMode mode : values()) {
            if (mode.settingValue.equalsIgnoreCase(normalized) || mode.name().equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        return PREVIEW;
    }
}
