package com.github.drafael.chat4j.stt;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.provider.CredentialSource;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperInstalledModel;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperModelCatalog;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperModelManagementService;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperModelManagementSnapshot;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperValidationStatus;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class SpeechToTextWhisperSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Whisper settings resolve available from management snapshot without native work")
    void resolve_whenWhisperSnapshotReady_returnsLocalReference() {
        var repo = new SettingsRepository(tempDir.resolve("settings.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_WHISPER);
        try (var service = new FakeWhisperModelManagementService(repo, tempDir.resolve("models"), tempDir.resolve("temp"), true)) {
            var subject = new SpeechToTextSettings(repo, SpeechToTextProviderRegistry.createDefault(), CredentialSource.SYSTEM, tempDir.resolve("models"), null, service);

            SpeechToTextSettingsSnapshot snapshot = subject.resolve();

            assertThat(snapshot.providerId()).isEqualTo("whisper");
            assertThat(snapshot.available()).isTrue();
            assertThat(snapshot.localModelReference()).isNotNull();
            assertThat(snapshot.localModelReference().modelId()).isEqualTo("tiny.en");
        }
    }

    @Test
    @DisplayName("Whisper settings require an explicit persisted local model selection")
    void resolve_whenWhisperInstalledButNotSelected_returnsUnavailable() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-no-selection.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_WHISPER);
        Path models = tempDir.resolve("real-models");
        var entry = WhisperModelCatalog.find("tiny.en").orElseThrow();
        createInstalledModel(models.resolve("whisper").resolve("tiny.en"), entry);
        try (var service = new WhisperModelManagementService(repo, models, tempDir.resolve("real-temp"))) {
            var subject = new SpeechToTextSettings(repo, SpeechToTextProviderRegistry.createDefault(), CredentialSource.SYSTEM, models, null, service);

            service.refreshAsync(false);
            waitUntil(() -> !service.snapshot().installedModels().isEmpty());
            SpeechToTextSettingsSnapshot snapshot = subject.resolve();

            assertThat(snapshot.available()).isFalse();
            assertThat(snapshot.model()).isNull();
            assertThat(snapshot.statusMessage()).contains("Select an installed Whisper.cpp model");
        }
    }

    @Test
    @DisplayName("Whisper settings report native runtime failure before model readiness")
    void resolve_whenWhisperRuntimeUnavailable_returnsUnavailableStatus() {
        var repo = new SettingsRepository(tempDir.resolve("settings-unavailable.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_WHISPER);
        try (var service = new FakeWhisperModelManagementService(repo, tempDir.resolve("models"), tempDir.resolve("temp"), false)) {
            var subject = new SpeechToTextSettings(repo, SpeechToTextProviderRegistry.createDefault(), CredentialSource.SYSTEM, tempDir.resolve("models"), null, service);

            SpeechToTextSettingsSnapshot snapshot = subject.resolve();

            assertThat(snapshot.available()).isFalse();
            assertThat(snapshot.statusMessage()).isEqualTo("Whisper.cpp native runtime is unavailable on this platform.");
        }
    }

    private void createInstalledModel(Path directory, com.github.drafael.chat4j.stt.provider.whisper.WhisperModelCatalogEntry entry) throws Exception {
        Files.createDirectories(directory);
        try (var file = new RandomAccessFile(directory.resolve("model.bin").toFile(), "rw")) {
            file.setLength(entry.sizeBytes());
        }
        Properties properties = new Properties();
        properties.setProperty("metadataVersion", "1");
        properties.setProperty("id", entry.id());
        properties.setProperty("sourceUrl", entry.downloadUri().toString());
        properties.setProperty("expectedFileName", entry.expectedFileName());
        properties.setProperty("sizeBytes", Long.toString(entry.sizeBytes()));
        properties.setProperty("sha1", entry.sha1());
        try (var output = Files.newOutputStream(directory.resolve("metadata.properties"))) {
            properties.store(output, "test");
        }
    }

    private void waitUntil(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.met() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.met()).isTrue();
    }

    @FunctionalInterface
    private interface Condition {
        boolean met();
    }

    private static final class FakeWhisperModelManagementService extends WhisperModelManagementService {
        private final WhisperModelManagementSnapshot snapshot;

        private FakeWhisperModelManagementService(SettingsRepository repo, Path models, Path temp, boolean runtimeReady) {
            super(repo, models, temp);
            var entry = WhisperModelCatalog.find("tiny.en").orElseThrow();
            var installed = new WhisperInstalledModel(
                    entry.id(),
                    entry.label(),
                    models.resolve("whisper/tiny.en"),
                    models.resolve("whisper/tiny.en"),
                    entry,
                    true,
                    WhisperValidationStatus.VALID,
                    "Installed",
                    "fingerprint"
            );
            this.snapshot = new WhisperModelManagementSnapshot(
                    models.resolve("whisper"),
                    temp,
                    WhisperModelCatalog.entries(),
                    List.of(installed),
                    emptyList(),
                    installed,
                    runtimeReady,
                    runtimeReady ? "Using Whisper.cpp model Whisper tiny.en locally." : "Whisper.cpp native runtime is unavailable on this platform.",
                    false,
                    "",
                    "",
                    "",
                    0,
                    0,
                    false,
                    false,
                    ""
            );
        }

        @Override
        public WhisperModelManagementSnapshot snapshot() {
            return snapshot;
        }
    }
}
