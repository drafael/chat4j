package com.github.drafael.chat4j.storage;

import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.sql.SQLException;
import java.util.Optional;

public final class ChatStorageConfig {

    public static final StorageBackend DEFAULT_BACKEND = StorageBackend.H2;

    private final StorageBackend activeBackend;
    private final StorageBackend pendingBackend;

    public ChatStorageConfig(@NonNull StorageBackend activeBackend, StorageBackend pendingBackend) {
        this.activeBackend = activeBackend;
        this.pendingBackend = pendingBackend;
    }

    public StorageBackend activeBackend() {
        return activeBackend;
    }

    public Optional<StorageBackend> pendingBackend() {
        return Optional.ofNullable(pendingBackend);
    }

    public Optional<StorageBackend> pendingMigrationTarget() {
        return pendingBackend().filter(backend -> backend != activeBackend);
    }

    public static ChatStorageConfig load(@NonNull SettingsRepo settingsRepo) throws SQLException {
        StorageBackend activeBackend = settingsRepo.get(SettingsKeys.CHAT_STORAGE_BACKEND_ACTIVE)
                .flatMap(StorageBackend::fromSettingValue)
                .orElse(DEFAULT_BACKEND);
        StorageBackend pendingBackend = settingsRepo.get(SettingsKeys.CHAT_STORAGE_BACKEND_PENDING)
                .filter(StringUtils::isNotBlank)
                .flatMap(StorageBackend::fromSettingValue)
                .orElse(null);
        return new ChatStorageConfig(activeBackend, pendingBackend);
    }

    public static void requestBackend(@NonNull SettingsRepo settingsRepo, @NonNull StorageBackend selectedBackend)
            throws SQLException {
        ChatStorageConfig config = load(settingsRepo);
        if (selectedBackend == config.activeBackend()) {
            settingsRepo.remove(SettingsKeys.CHAT_STORAGE_BACKEND_PENDING);
            return;
        }

        settingsRepo.put(SettingsKeys.CHAT_STORAGE_BACKEND_PENDING, selectedBackend.settingValue());
    }

    public static void markActive(@NonNull SettingsRepo settingsRepo, @NonNull StorageBackend activeBackend)
            throws SQLException {
        settingsRepo.put(SettingsKeys.CHAT_STORAGE_BACKEND_ACTIVE, activeBackend.settingValue());
        settingsRepo.remove(SettingsKeys.CHAT_STORAGE_BACKEND_PENDING);
    }
}
