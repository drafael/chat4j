package com.github.drafael.chat4j.persistence.db;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatStorageSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Storage settings default to SQLite when no backend is configured")
    void load_whenNoBackendConfigured_defaultsToSqlite() throws Exception {
        var settingsRepo = settingsRepo("chat-storage-config-default");
        var subject = new ChatStorageSettings(settingsRepo);

        PersistenceBackendConfig config = subject.load();

        assertThat(config.activeBackend()).isEqualTo(StorageBackend.SQLITE);
        assertThat(config.pendingMigrationTarget()).isEmpty();
    }

    @Test
    @DisplayName("Requesting a different backend stores it as pending")
    void requestBackend_whenBackendDiffers_storesPendingBackend() throws Exception {
        var settingsRepo = settingsRepo("chat-storage-config-pending");
        var subject = new ChatStorageSettings(settingsRepo);

        subject.requestBackend(StorageBackend.H2);

        assertThat(settingsRepo.get("chat.storage.backend.pending")).contains("h2");
        assertThat(subject.load().pendingMigrationTarget()).contains(StorageBackend.H2);
    }

    @Test
    @DisplayName("Requesting the active backend clears pending storage migration")
    void requestBackend_whenBackendMatchesActive_clearsPendingBackend() throws Exception {
        var settingsRepo = settingsRepo("chat-storage-config-clear-pending");
        settingsRepo.put("chat.storage.backend.pending", StorageBackend.H2.settingValue());
        var subject = new ChatStorageSettings(settingsRepo);

        subject.requestBackend(StorageBackend.SQLITE);

        assertThat(settingsRepo.get("chat.storage.backend.pending")).isEmpty();
    }

    @Test
    @DisplayName("Marking active backend persists active and clears pending backend")
    void markActive_whenCalled_persistsActiveAndClearsPending() throws Exception {
        var settingsRepo = settingsRepo("chat-storage-mark-active");
        settingsRepo.put("chat.storage.backend.pending", StorageBackend.SQLITE.settingValue());
        var subject = new ChatStorageSettings(settingsRepo);

        subject.markActive(StorageBackend.H2);

        assertThat(settingsRepo.get("chat.storage.backend.active")).contains("h2");
        assertThat(settingsRepo.get("chat.storage.backend.pending")).isEmpty();
    }

    @Test
    @DisplayName("Invalid or blank pending backend is ignored")
    void load_whenPendingBackendInvalidOrBlank_ignoresPendingBackend() throws Exception {
        var settingsRepo = settingsRepo("chat-storage-invalid-pending");
        settingsRepo.put("chat.storage.backend.pending", "   ");
        var subject = new ChatStorageSettings(settingsRepo);

        assertThat(subject.load().pendingMigrationTarget()).isEmpty();

        settingsRepo.put("chat.storage.backend.pending", "missing");

        assertThat(subject.load().pendingMigrationTarget()).isEmpty();
    }

    @Test
    @DisplayName("Raw absence detection only returns true when both backend keys are absent")
    void hasNoStoredBackendSelection_whenKeysAbsent_returnsTrue() throws Exception {
        var settingsRepo = settingsRepo("chat-storage-raw-absence");
        var subject = new ChatStorageSettings(settingsRepo);

        assertThat(subject.hasNoStoredBackendSelection()).isTrue();

        settingsRepo.put("chat.storage.backend.active", "invalid-but-present");

        assertThat(subject.hasNoStoredBackendSelection()).isFalse();
    }

    @Test
    @DisplayName("Repository failures propagate from reads")
    void load_whenRepositoryReadFails_propagatesFailure() {
        var subject = new ChatStorageSettings(new ThrowingReadSettingsRepo());

        assertThatThrownBy(subject::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forced failure");
    }

    @Test
    @DisplayName("Repository failures propagate from writes")
    void requestBackend_whenRepositoryFails_propagatesFailure() {
        var subject = new ChatStorageSettings(new ThrowingSettingsRepo());

        assertThatThrownBy(() -> subject.requestBackend(StorageBackend.H2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forced failure");
    }

    private SettingsRepository settingsRepo(String testName) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(testName)));
    }

    private static class ThrowingReadSettingsRepo extends SettingsRepository {

        private ThrowingReadSettingsRepo() {
            super(Path.of("unused-chat-storage-read-settings.properties"));
        }

        @Override
        public Optional<String> get(String key) {
            throw new IllegalStateException("forced failure");
        }
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private ThrowingSettingsRepo() {
            super(Path.of("unused-chat-storage-settings.properties"));
        }

        @Override
        public Optional<String> get(String key) {
            return Optional.empty();
        }

        @Override
        public void put(String key, String value) {
            throw new IllegalStateException("forced failure");
        }
    }
}
