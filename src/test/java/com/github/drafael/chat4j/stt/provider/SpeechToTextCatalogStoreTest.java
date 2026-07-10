package com.github.drafael.chat4j.stt.provider;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.provider.assemblyai.AssemblyAiSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.deepgram.DeepgramSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.elevenlabs.ElevenLabsSpeechToTextProvider;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class SpeechToTextCatalogStoreTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Saved STT catalog uses legacy provider-scoped keys")
    void saveModels_whenProviderKnown_writesLegacyKeys() throws Exception {
        var repo = repo("save.properties");
        var subject = new SpeechToTextCatalogStore(repo);

        subject.saveModels(ElevenLabsSpeechToTextProvider.ID, List.of(SpeechToTextCatalogItem.of("scribe_v2", "Scribe v2")));

        assertThat(repo.get("chat4j.stt.catalog.elevenlabs.models", "")).contains("scribe_v2");
        assertThat(repo.get("chat4j.stt.catalog.elevenlabs.updatedAt")).isPresent();
        assertThat(subject.cachedModels(ElevenLabsSpeechToTextProvider.ID))
                .extracting(SpeechToTextCatalogItem::id)
                .containsExactly("scribe_v2");
    }

    @Test
    @DisplayName("Malformed STT catalog falls back to empty cached models")
    void cachedModels_whenJsonMalformed_returnsEmptyList() {
        var repo = repo("malformed.properties");
        repo.put("chat4j.stt.catalog.deepgram.models", "not json");
        var subject = new SpeechToTextCatalogStore(repo);

        assertThat(subject.cachedModels(DeepgramSpeechToTextProvider.ID)).isEmpty();
    }

    @Test
    @DisplayName("STT stale timestamps keep existing 24-hour behavior")
    void stale_whenTimestampOlderThanWindow_returnsTrue() throws Exception {
        var repo = repo("stale.properties");
        repo.put("chat4j.stt.catalog.deepgram.updatedAt", "2000-01-01T00:00:00Z");
        var subject = new SpeechToTextCatalogStore(repo);

        assertThat(subject.stale(DeepgramSpeechToTextProvider.ID)).isTrue();

        subject.saveModels(DeepgramSpeechToTextProvider.ID, List.of(SpeechToTextCatalogItem.of("nova-3", "Nova 3")));

        assertThat(subject.stale(DeepgramSpeechToTextProvider.ID)).isFalse();
    }

    @Test
    @DisplayName("AssemblyAI catalog exposes only bundled official models")
    void models_whenAssemblyAiSelected_usesBundledOfficialModelsOnly() throws Exception {
        var repo = repo("assemblyai.properties");
        var subject = new SpeechToTextCatalogStore(repo);
        var provider = new AssemblyAiSpeechToTextProvider((request, cancellationToken) -> new SttHttpResponse(200, null, new byte[0]));
        subject.saveModels(AssemblyAiSpeechToTextProvider.ID, List.of(SpeechToTextCatalogItem.of("stale-model", "Stale Model")));

        var models = subject.models(provider, SpeechToTextCatalogItem.of("selected-stale", "Selected Stale"));

        assertThat(models).extracting(SpeechToTextCatalogItem::id)
                .containsExactly("assemblyai-auto", "universal-3-5-pro", "universal-2");
    }

    private SettingsRepository repo(String fileName) {
        return new SettingsRepository(tempDir.resolve(fileName));
    }
}
