package com.github.drafael.chat4j.storage;

import java.util.Arrays;
import java.util.Optional;

import static org.apache.commons.lang3.StringUtils.isBlank;

public enum StorageBackend {
    H2("h2", "H2"),
    SQLITE("sqlite", "SQLite");

    private final String settingValue;
    private final String displayName;

    StorageBackend(String settingValue, String displayName) {
        this.settingValue = settingValue;
        this.displayName = displayName;
    }

    public String settingValue() {
        return settingValue;
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

    public static Optional<StorageBackend> fromSettingValue(String value) {
        if (isBlank(value)) {
            return Optional.empty();
        }

        String normalized = value.trim();
        return Arrays.stream(values())
                .filter(backend -> backend.settingValue.equalsIgnoreCase(normalized)
                        || backend.name().equalsIgnoreCase(normalized))
                .findFirst();
    }
}
