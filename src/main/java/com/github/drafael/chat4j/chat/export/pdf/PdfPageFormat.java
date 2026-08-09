package com.github.drafael.chat4j.chat.export.pdf;

import org.apache.commons.lang3.Strings;

public enum PdfPageFormat {
    A4("a4", "A4", "A4", "a4paper", 210.0f, 297.0f),
    US_LETTER("us-letter", "US Letter", "Letter", "letterpaper", 215.9f, 279.4f);

    private final String settingValue;
    private final String displayName;
    private final String cssPageSize;
    private final String latexPaperOption;
    private final float widthMillimeters;
    private final float heightMillimeters;

    PdfPageFormat(
            String settingValue,
            String displayName,
            String cssPageSize,
            String latexPaperOption,
            float widthMillimeters,
            float heightMillimeters
    ) {
        this.settingValue = settingValue;
        this.displayName = displayName;
        this.cssPageSize = cssPageSize;
        this.latexPaperOption = latexPaperOption;
        this.widthMillimeters = widthMillimeters;
        this.heightMillimeters = heightMillimeters;
    }

    public String settingValue() {
        return settingValue;
    }

    public String cssPageSize() {
        return cssPageSize;
    }

    public String latexPaperOption() {
        return latexPaperOption;
    }

    public float widthMillimeters() {
        return widthMillimeters;
    }

    public float heightMillimeters() {
        return heightMillimeters;
    }

    public double widthInches() {
        return widthMillimeters / 25.4;
    }

    public double heightInches() {
        return heightMillimeters / 25.4;
    }

    public static PdfPageFormat fromSettingValue(String value) {
        for (PdfPageFormat format : values()) {
            if (Strings.CI.equals(format.settingValue, value)) {
                return format;
            }
        }
        return A4;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
