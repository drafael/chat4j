package com.github.drafael.chat4j.stt;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.provider.CredentialSource;
import com.github.drafael.chat4j.stt.provider.vosk.VoskModelManagementService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpeechToTextVoskSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Vosk stays selectable but unavailable when no model is installed")
    void resolve_whenVoskHasNoInstalledModel_isSelectableUnavailable() {
        var repo = new SettingsRepository(tempDir.resolve("settings.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_VOSK);
        Path models = tempDir.resolve("models");
        try (var voskModels = new VoskModelManagementService(repo, models, tempDir.resolve("temp"))) {
            var subject = new SpeechToTextSettings(repo, SpeechToTextProviderRegistry.createDefault(), credentials(), models, voskModels);

            var snapshot = subject.resolve();

            assertThat(snapshot.enabled()).isTrue();
            assertThat(snapshot.providerId()).isEqualTo(SettingsKeys.STT_PROVIDER_VOSK);
            assertThat(snapshot.available()).isFalse();
            assertThat(snapshot.model()).isNull();
            assertThat(snapshot.statusMessage()).contains("Download or add");
        }
    }

    @Test
    @DisplayName("Plausible Vosk layouts stay visible but are not selectable or ready")
    void resolve_whenVoskModelIsPlausibleUnverified_keepsVisibleButUnavailable() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-plausible.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_VOSK);
        Path models = tempDir.resolve("plausible-models");
        Path plausibleModel = models.resolve("vosk").resolve("custom-plausible");
        Files.createDirectories(plausibleModel.resolve("am"));
        try (var voskModels = new VoskModelManagementService(repo, models, tempDir.resolve("plausible-temp"))) {
            var subject = new SpeechToTextSettings(repo, SpeechToTextProviderRegistry.createDefault(), credentials(), models, voskModels);
            voskModels.refreshAsync();
            waitUntil(() -> !voskModels.snapshot().installedModels().isEmpty());

            var snapshot = subject.resolve();

            assertThat(snapshot.available()).isFalse();
            assertThat(snapshot.model()).isNull();
            assertThat(voskModels.snapshot().rows())
                    .filteredOn(row -> "local:custom-plausible".equals(row.id()))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.installed()).isTrue();
                        assertThat(row.selectable()).isFalse();
                        assertThat(row.actionStatus()).contains("plausible");
                    });
            assertThatThrownBy(() -> voskModels.selectModel("local:custom-plausible"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Select an installed Vosk model first");
        }
    }

    @Test
    @DisplayName("Vosk resolves installed model without requiring a cloud endpoint")
    void resolve_whenVoskHasInstalledModel_usesLocalReference() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_VOSK);
        Path models = tempDir.resolve("models");
        createValidModel(models.resolve("vosk").resolve("vosk-model-small-en-us-0.15"));
        try (var voskModels = new VoskModelManagementService(repo, models, tempDir.resolve("temp"))) {
            var subject = new SpeechToTextSettings(repo, SpeechToTextProviderRegistry.createDefault(), credentials(), models, voskModels);
            voskModels.refreshAsync();
            waitUntil(() -> subject.resolve().model() != null);

            var snapshot = subject.resolve();

            assertThat(snapshot.enabled()).isTrue();
            assertThat(snapshot.model().id()).isEqualTo("vosk-model-small-en-us-0.15");
            assertThat(snapshot.baseUri()).isNull();
            assertThat(snapshot.transcriptionUri()).isNull();
            assertThat(snapshot.localModelReference()).isNotNull();
        }
    }

    private void waitUntil(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.met() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.met()).isTrue();
    }

    private void createValidModel(Path model) throws Exception {
        Files.createDirectories(model.resolve("am"));
        Files.createDirectories(model.resolve("conf"));
        Files.createDirectories(model.resolve("graph"));
        Files.writeString(model.resolve("am").resolve("final.mdl"), "model");
        Files.writeString(model.resolve("conf").resolve("model.conf"), "conf");
        Files.writeString(model.resolve("conf").resolve("mfcc.conf"), "mfcc");
        Files.writeString(model.resolve("graph").resolve("HCLG.fst"), "graph");
    }

    private CredentialSource credentials() {
        return new CredentialSource() {
            @Override
            public boolean hasRequiredCredentials(String envVar) {
                return true;
            }

            @Override
            public String resolveRequiredApiKey(String envVar) {
                return "";
            }
        };
    }

    @FunctionalInterface
    private interface Condition {
        boolean met();
    }
}
