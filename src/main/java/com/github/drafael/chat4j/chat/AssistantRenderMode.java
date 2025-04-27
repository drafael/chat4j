package com.github.drafael.chat4j.chat;

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
        if (value == null || value.isBlank()) {
            return PREVIEW;
        }

        for (AssistantRenderMode mode : values()) {
            if (mode.settingValue.equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return PREVIEW;
    }
}
