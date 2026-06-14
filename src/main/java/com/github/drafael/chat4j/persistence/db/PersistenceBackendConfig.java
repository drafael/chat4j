package com.github.drafael.chat4j.persistence.db;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.sql.SQLException;
import java.util.Optional;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

public final class PersistenceBackendConfig {

    public static final StorageBackend DEFAULT_BACKEND = StorageBackend.H2;

    private final StorageBackend activeBackend;
    private final StorageBackend pendingBackend;

    public PersistenceBackendConfig(@NonNull StorageBackend activeBackend, StorageBackend pendingBackend) {
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

    public static PersistenceBackendConfig load(@NonNull SettingsRepository settingsRepo) throws SQLException {
        StorageBackend activeBackend = settingsRepo.get(SettingsKeys.CHAT_STORAGE_BACKEND_ACTIVE)
                .flatMap(StorageBackend::fromSettingValue)
                .orElse(DEFAULT_BACKEND);
        StorageBackend pendingBackend = settingsRepo.get(SettingsKeys.CHAT_STORAGE_BACKEND_PENDING)
                .filter(StringUtils::isNotBlank)
                .flatMap(StorageBackend::fromSettingValue)
                .orElse(null);
        return new PersistenceBackendConfig(activeBackend, pendingBackend);
    }

    public static void requestBackend(@NonNull SettingsRepository settingsRepo, @NonNull StorageBackend selectedBackend)
            throws SQLException {
        PersistenceBackendConfig config = load(settingsRepo);
        if (selectedBackend == config.activeBackend()) {
            settingsRepo.remove(SettingsKeys.CHAT_STORAGE_BACKEND_PENDING);
            return;
        }

        settingsRepo.put(SettingsKeys.CHAT_STORAGE_BACKEND_PENDING, selectedBackend.settingValue());
    }

    public static void markActive(@NonNull SettingsRepository settingsRepo, @NonNull StorageBackend activeBackend)
            throws SQLException {
        settingsRepo.put(SettingsKeys.CHAT_STORAGE_BACKEND_ACTIVE, activeBackend.settingValue());
        settingsRepo.remove(SettingsKeys.CHAT_STORAGE_BACKEND_PENDING);
    }
}
