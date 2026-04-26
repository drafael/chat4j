package com.github.drafael.chat4j.chat;

import org.apache.commons.lang3.StringUtils;

public enum AssistantRenderMode {
    PREVIEW("preview", "Preview"),
    MARKDOWN("markdown", "Markdown");

    private final String settingValue;
    private final String displayName;

    AssistantRenderMode(String settingValue, String displayName) {
        this.settingValue = settingValue;
        this.displayName = displayName;
    }

    public String settingValue() {
        return settingValue;
    }

    public String displayName() {
        return displayName;
    }

    public static AssistantRenderMode fromSettingValue(String value) {
        if (StringUtils.isBlank(value)) {
            return PREVIEW;
        }

        String normalized = value.trim();
        for (AssistantRenderMode mode : values()) {
            if (mode.settingValue.equalsIgnoreCase(normalized) || mode.name().equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        return PREVIEW;
    }
}
