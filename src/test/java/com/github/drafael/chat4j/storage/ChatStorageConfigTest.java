package com.github.drafael.chat4j.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ChatStorageConfigTest {

    @Test
    @DisplayName("Storage config defaults to H2 when no backend is configured")
    void load_whenNoBackendConfigured_defaultsToH2() throws Exception {
        var settingsRepo = settingsRepo("chat-storage-config-default");

        ChatStorageConfig subject = ChatStorageConfig.load(settingsRepo);

        assertThat(subject.activeBackend()).isEqualTo(StorageBackend.H2);
        assertThat(subject.pendingMigrationTarget()).isEmpty();
    }

    @Test
    @DisplayName("Requesting a different backend stores it as pending")
    void requestBackend_whenBackendDiffers_storesPendingBackend() throws Exception {
        var settingsRepo = settingsRepo("chat-storage-config-pending");

        ChatStorageConfig.requestBackend(settingsRepo, StorageBackend.SQLITE);

        assertThat(settingsRepo.get(SettingsKeys.CHAT_STORAGE_BACKEND_PENDING)).contains("sqlite");
        assertThat(ChatStorageConfig.load(settingsRepo).pendingMigrationTarget()).contains(StorageBackend.SQLITE);
    }

    @Test
    @DisplayName("Requesting the active backend clears pending storage migration")
    void requestBackend_whenBackendMatchesActive_clearsPendingBackend() throws Exception {
        var settingsRepo = settingsRepo("chat-storage-config-clear-pending");
        settingsRepo.put(SettingsKeys.CHAT_STORAGE_BACKEND_PENDING, StorageBackend.SQLITE.settingValue());

        ChatStorageConfig.requestBackend(settingsRepo, StorageBackend.H2);

        assertThat(settingsRepo.get(SettingsKeys.CHAT_STORAGE_BACKEND_PENDING)).isEmpty();
    }

    private SettingsRepo settingsRepo(String testName) throws Exception {
        Path settingsFile = Path.of("target", "%s.properties".formatted(testName));
        Files.deleteIfExists(settingsFile);
        return new SettingsRepo(settingsFile);
    }
}
