package com.github.drafael.chat4j.chat.render;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.Arrays;

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
        String normalized = StringUtils.trimToEmpty(value);
        return Arrays.stream(values())
                .filter(mode -> Strings.CI.equals(mode.settingValue, normalized) || Strings.CI.equals(mode.name(), normalized))
                .findFirst()
                .orElse(PREVIEW);
    }
}
