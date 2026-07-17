package com.github.drafael.chat4j.persistence.catalog;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ObsoleteSpeechCatalogSettingsCleanupTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Inline payload cleanup preserves selections and existing snapshot references")
    void cleanup_whenInlinePayloadExists_removesPayloadAndTimestampOnly() {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        settings.put("chat4j.tts.catalog.elevenlabs.models", "oversized-or-malformed-content-is-not-parsed");
        settings.put("chat4j.tts.catalog.elevenlabs.updatedAt", "2026-07-15T00:00:00Z");
        settings.put("chat4j.tts.catalog.elevenlabs.modelsFile", "tts-elevenlabs-models-12345678123412341234123456789012.json");
        settings.put("chat4j.tts.elevenlabs.model.id", "selected-model");

        new ObsoleteSpeechCatalogSettingsCleanup(settings).cleanup();

        assertThat(settings.get("chat4j.tts.catalog.elevenlabs.models")).isEmpty();
        assertThat(settings.get("chat4j.tts.catalog.elevenlabs.updatedAt")).isEmpty();
        assertThat(settings.get("chat4j.tts.catalog.elevenlabs.modelsFile"))
                .contains("tts-elevenlabs-models-12345678123412341234123456789012.json");
        assertThat(settings.get("chat4j.tts.elevenlabs.model.id")).contains("selected-model");
    }

    @Test
    @DisplayName("Cleanup handles every obsolete speech key family without removing unrelated timestamps or current state")
    void cleanup_whenAllInlineFamiliesExist_removesOnlyObsoletePayloadsAndAssociatedTimestamps() {
        var settings = new SettingsRepository(tempDir.resolve("all-families.properties"));
        settings.put("chat4j.stt.catalog.deepgram.models", "stt-inline");
        settings.put("chat4j.stt.catalog.deepgram.updatedAt", "stt-time");
        settings.put("chat4j.tts.catalog.elevenlabs.voices", "tts-inline");
        settings.put("chat4j.tts.catalog.elevenlabs.updatedAt", "tts-time");
        settings.put("chat4j.stt.catalog.vosk.rawJson", "vosk-inline");
        settings.put("chat4j.stt.catalog.vosk.rawJson.updatedAt", "vosk-time");
        settings.put("chat4j.stt.catalog.groq.updatedAt", "timestamp-only");
        settings.put("chat4j.stt.catalog.deepgram.modelsFile", "current-stt-reference");
        settings.put("chat4j.tts.catalog.elevenlabs.voicesFile", "current-tts-reference");
        settings.put("chat4j.stt.catalog.vosk.rawJsonFile", "current-vosk-reference");
        settings.put("chat4j.stt.deepgram.model.id", "selected-model");

        new ObsoleteSpeechCatalogSettingsCleanup(settings).cleanup();

        assertThat(settings.get("chat4j.stt.catalog.deepgram.models")).isEmpty();
        assertThat(settings.get("chat4j.stt.catalog.deepgram.updatedAt")).isEmpty();
        assertThat(settings.get("chat4j.tts.catalog.elevenlabs.voices")).isEmpty();
        assertThat(settings.get("chat4j.tts.catalog.elevenlabs.updatedAt")).isEmpty();
        assertThat(settings.get("chat4j.stt.catalog.vosk.rawJson")).isEmpty();
        assertThat(settings.get("chat4j.stt.catalog.vosk.rawJson.updatedAt")).isEmpty();
        assertThat(settings.get("chat4j.stt.catalog.groq.updatedAt")).contains("timestamp-only");
        assertThat(settings.get("chat4j.stt.catalog.deepgram.modelsFile")).contains("current-stt-reference");
        assertThat(settings.get("chat4j.tts.catalog.elevenlabs.voicesFile")).contains("current-tts-reference");
        assertThat(settings.get("chat4j.stt.catalog.vosk.rawJsonFile")).contains("current-vosk-reference");
        assertThat(settings.get("chat4j.stt.deepgram.model.id")).contains("selected-model");
    }

    @Test
    @DisplayName("A failed cleanup batch leaves obsolete settings untouched for a later retry")
    void cleanup_whenBatchCommitFails_preservesExistingSettings() {
        var settings = new FailingPrefixBatchSettingsRepository(tempDir.resolve("failed.properties"));
        settings.put("chat4j.stt.catalog.deepgram.models", "inline");
        settings.put("chat4j.stt.catalog.deepgram.updatedAt", "timestamp");
        settings.failUpdates = true;

        new ObsoleteSpeechCatalogSettingsCleanup(settings).cleanup();

        assertThat(settings.get("chat4j.stt.catalog.deepgram.models")).contains("inline");
        assertThat(settings.get("chat4j.stt.catalog.deepgram.updatedAt")).contains("timestamp");
    }

    private static final class FailingPrefixBatchSettingsRepository extends SettingsRepository {
        private boolean failUpdates;

        private FailingPrefixBatchSettingsRepository(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public void updatePrefixBatch(String prefix, int maxEntries, Consumer<PrefixBatchUpdate> updates) {
            if (failUpdates) {
                throw new IllegalStateException("simulated settings failure");
            }
            super.updatePrefixBatch(prefix, maxEntries, updates);
        }
    }
}
