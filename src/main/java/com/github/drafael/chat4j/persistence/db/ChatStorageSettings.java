package com.github.drafael.chat4j.persistence.db;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

public final class ChatStorageSettings {

    private static final String KEY_ACTIVE_BACKEND = "chat.storage.backend.active";
    private static final String KEY_PENDING_BACKEND = "chat.storage.backend.pending";

    private final SettingsRepository settingsRepo;

    public ChatStorageSettings(@NonNull SettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    public PersistenceBackendConfig load() {
        StorageBackend activeBackend = settingsRepo.get(KEY_ACTIVE_BACKEND)
                .flatMap(StorageBackend::fromSettingValue)
                .orElse(PersistenceBackendConfig.DEFAULT_BACKEND);
        StorageBackend pendingBackend = settingsRepo.get(KEY_PENDING_BACKEND)
                .filter(StringUtils::isNotBlank)
                .flatMap(StorageBackend::fromSettingValue)
                .orElse(null);
        return new PersistenceBackendConfig(activeBackend, pendingBackend);
    }

    public void requestBackend(@NonNull StorageBackend selectedBackend) {
        PersistenceBackendConfig config = load();
        if (selectedBackend == config.activeBackend()) {
            settingsRepo.remove(KEY_PENDING_BACKEND);
            return;
        }

        settingsRepo.put(KEY_PENDING_BACKEND, selectedBackend.settingValue());
    }

    public void markActive(@NonNull StorageBackend activeBackend) {
        settingsRepo.put(KEY_ACTIVE_BACKEND, activeBackend.settingValue());
        settingsRepo.remove(KEY_PENDING_BACKEND);
    }

    public boolean hasNoStoredBackendSelection() {
        return settingsRepo.get(KEY_ACTIVE_BACKEND).isEmpty()
                && settingsRepo.get(KEY_PENDING_BACKEND).isEmpty();
    }
}
