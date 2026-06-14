package com.github.drafael.chat4j.persistence.db;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceBackendConfigTest {

    @Test
    @DisplayName("Storage config defaults to SQLite when no backend is configured")
    void load_whenNoBackendConfigured_defaultsToSqlite() throws Exception {
        var settingsRepo = settingsRepo("chat-storage-config-default");

        PersistenceBackendConfig subject = PersistenceBackendConfig.load(settingsRepo);

        assertThat(subject.activeBackend()).isEqualTo(StorageBackend.SQLITE);
        assertThat(subject.pendingMigrationTarget()).isEmpty();
    }

    @Test
    @DisplayName("Requesting a different backend stores it as pending")
    void requestBackend_whenBackendDiffers_storesPendingBackend() throws Exception {
        var settingsRepo = settingsRepo("chat-storage-config-pending");

        PersistenceBackendConfig.requestBackend(settingsRepo, StorageBackend.H2);

        assertThat(settingsRepo.get(SettingsKeys.CHAT_STORAGE_BACKEND_PENDING)).contains("h2");
        assertThat(PersistenceBackendConfig.load(settingsRepo).pendingMigrationTarget()).contains(StorageBackend.H2);
    }

    @Test
    @DisplayName("Requesting the active backend clears pending storage migration")
    void requestBackend_whenBackendMatchesActive_clearsPendingBackend() throws Exception {
        var settingsRepo = settingsRepo("chat-storage-config-clear-pending");
        settingsRepo.put(SettingsKeys.CHAT_STORAGE_BACKEND_PENDING, StorageBackend.H2.settingValue());

        PersistenceBackendConfig.requestBackend(settingsRepo, StorageBackend.SQLITE);

        assertThat(settingsRepo.get(SettingsKeys.CHAT_STORAGE_BACKEND_PENDING)).isEmpty();
    }

    private SettingsRepository settingsRepo(String testName) throws Exception {
        Path settingsFile = Path.of("target", "%s.properties".formatted(testName));
        Files.deleteIfExists(settingsFile);
        return new SettingsRepository(settingsFile);
    }
}
