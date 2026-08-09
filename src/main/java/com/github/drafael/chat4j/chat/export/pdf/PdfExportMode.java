package com.github.drafael.chat4j.chat.export.pdf;

import org.apache.commons.lang3.Strings;

public enum PdfExportMode {
    AUTO("auto", "Auto"),
    STANDARD("standard", "Built-in Standard"),
    PUBLICATION("publication", "Publication (Pandoc + LaTeX)");

    private final String settingValue;
    private final String displayName;

    PdfExportMode(String settingValue, String displayName) {
        this.settingValue = settingValue;
        this.displayName = displayName;
    }

    public String settingValue() {
        return settingValue;
    }

    public static PdfExportMode fromSettingValue(String value) {
        for (PdfExportMode mode : values()) {
            if (Strings.CI.equals(mode.settingValue, value)) {
                return mode;
            }
        }
        return AUTO;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
