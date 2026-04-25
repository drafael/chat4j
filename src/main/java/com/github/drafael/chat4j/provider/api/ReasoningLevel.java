package com.github.drafael.chat4j.provider.api;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

public enum ReasoningLevel {
    OFF,
    LOW,
    MEDIUM,
    HIGH,
    EXTRA_HIGH;

    public boolean enabled() {
        return this != OFF;
    }

    public String toSettingValue() {
        return switch (this) {
            case OFF -> "off";
            case LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH -> "high";
            case EXTRA_HIGH -> "extra_high";
        };
    }

    public static ReasoningLevel fromSettingValue(String value, ReasoningLevel fallback) {
        if (StringUtils.isBlank(value)) {
            return fallback;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');

        return switch (normalized) {
            case "off", "none" -> OFF;
            case "low" -> LOW;
            case "medium" -> MEDIUM;
            case "high" -> HIGH;
            case "extra_high", "xhigh" -> EXTRA_HIGH;
            default -> fallback;
        };
    }
}
