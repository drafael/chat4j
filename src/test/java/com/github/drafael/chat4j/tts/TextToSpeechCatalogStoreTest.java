package com.github.drafael.chat4j.tts;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogStore;
import com.github.drafael.chat4j.tts.provider.TextToSpeechProviderSettingsFactory;
import com.github.drafael.chat4j.tts.provider.TtsHttpResponse;
import com.github.drafael.chat4j.tts.provider.deepgram.DeepgramTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.elevenlabs.ElevenLabsTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.groq.GroqTextToSpeechProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class TextToSpeechCatalogStoreTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Saved catalogs use immutable snapshot references")
    void saveCatalogs_whenElevenLabsProvider_writesSnapshotReferencesAndReadsCatalogs() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-catalog", ".properties"));
        var provider = new ElevenLabsTextToSpeechProvider(request -> new TtsHttpResponse(200, null, new byte[0]));
        var subject = new TextToSpeechCatalogStore(settingsRepo);

        subject.saveCatalogs(
                ElevenLabsTextToSpeechProvider.ID,
                List.of(TextToSpeechCatalogItem.of("eleven_turbo_v2_5", "Eleven Turbo")),
                List.of(TextToSpeechCatalogItem.of("voice-a", "Voice A"))
        );

        assertThat(settingsRepo.get("chat4j.tts.catalog.elevenlabs.modelsFile"))
                .hasValueSatisfying(reference -> assertThat(reference).startsWith("tts-elevenlabs-models-"));
        assertThat(settingsRepo.get("chat4j.tts.catalog.elevenlabs.voicesFile"))
                .hasValueSatisfying(reference -> assertThat(reference).startsWith("tts-elevenlabs-voices-"));
        assertThat(settingsRepo.get("chat4j.tts.catalog.elevenlabs.models")).isEmpty();
        assertThat(settingsRepo.get("chat4j.tts.catalog.elevenlabs.voices")).isEmpty();
        assertThat(settingsRepo.get("chat4j.tts.catalog.elevenlabs.updatedAt"))
                .hasValueSatisfying(value -> assertThat(Instant.parse(value)).isNotNull());
        assertThat(subject.models(provider, provider.defaultModel()))
                .extracting(TextToSpeechCatalogItem::id)
                .contains("eleven_turbo_v2_5");
        assertThat(subject.voices(provider, provider.defaultVoice()))
                .extracting(TextToSpeechCatalogItem::id)
                .contains("voice-a");
    }

    @Test
    @DisplayName("Models and voices can be saved together in one catalog update")
    void saveCatalogs_whenModelsAndVoicesProvided_writesBothCatalogs() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-catalog", ".properties"));
        var provider = new ElevenLabsTextToSpeechProvider(request -> new TtsHttpResponse(200, null, new byte[0]));
        var subject = new TextToSpeechCatalogStore(settingsRepo);

        subject.saveCatalogs(
                ElevenLabsTextToSpeechProvider.ID,
                List.of(TextToSpeechCatalogItem.of("eleven_multilingual_v2", "Eleven Multilingual")),
                List.of(TextToSpeechCatalogItem.of("voice-b", "Voice B"))
        );

        assertThat(subject.models(provider, provider.defaultModel()))
                .extracting(TextToSpeechCatalogItem::id)
                .contains("eleven_multilingual_v2");
        assertThat(subject.voices(provider, provider.defaultVoice()))
                .extracting(TextToSpeechCatalogItem::id)
                .contains("voice-b");
        assertThat(settingsRepo.get("chat4j.tts.catalog.elevenlabs.updatedAt")).isPresent();
    }

    @Test
    @DisplayName("Saved selection is included when absent from cached catalog")
    void voices_savedSelectionAbsent_includesSavedSelection() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-catalog", ".properties"));
        var provider = new ElevenLabsTextToSpeechProvider(request -> new TtsHttpResponse(200, null, new byte[0]));
        var subject = new TextToSpeechCatalogStore(settingsRepo);
        subject.saveCatalogs(
                ElevenLabsTextToSpeechProvider.ID,
                List.of(),
                List.of(TextToSpeechCatalogItem.of("voice-a", "Voice A"))
        );

        var voices = subject.voices(provider, TextToSpeechCatalogItem.of("saved-voice", "Saved Voice"));

        assertThat(voices).extracting(TextToSpeechCatalogItem::id).containsExactly("voice-a", "saved-voice");
    }

    @Test
    @DisplayName("Cached catalog ignores null entries")
    void voices_cachedCatalogContainsNull_ignoresNullEntry() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-catalog", ".properties"));
        var provider = new ElevenLabsTextToSpeechProvider(request -> new TtsHttpResponse(200, null, new byte[0]));
        var subject = new TextToSpeechCatalogStore(settingsRepo);
        subject.saveCatalogs(
                ElevenLabsTextToSpeechProvider.ID,
                List.of(),
                Stream.of(null, TextToSpeechCatalogItem.of("voice-a", "Voice A")).toList()
        );

        var voices = subject.voices(provider, provider.defaultVoice());

        assertThat(voices).extracting(TextToSpeechCatalogItem::id).contains("voice-a");
    }

    @Test
    @DisplayName("Deepgram stale cached voice-model catalogs are shown as model families")
    void models_whenDeepgramCacheContainsLegacyVoiceModels_normalizesToFamilies() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-catalog", ".properties"));
        var provider = new DeepgramTextToSpeechProvider(request -> new TtsHttpResponse(200, null, new byte[0]));
        var subject = new TextToSpeechCatalogStore(settingsRepo);
        subject.saveCatalogs(
                DeepgramTextToSpeechProvider.ID,
                List.of(
                        new TextToSpeechCatalogItem("aura-2-thalia-en", "thalia", "clear"),
                        new TextToSpeechCatalogItem("aura-2-zeus-en", "zeus", "deep")
                ),
                List.of()
        );

        var models = subject.models(provider, TextToSpeechCatalogItem.of("aura-2-thalia-en", "thalia"));

        assertThat(models).extracting(TextToSpeechCatalogItem::id).containsExactly("aura-2");
        assertThat(models.getFirst().label()).isEqualTo("Aura 2");
    }

    @Test
    @DisplayName("Invalidating TTS catalog clears stale selected model and voice")
    void invalidate_whenProviderHasSelections_removesTimestampAndSelections() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-invalidate", ".properties"));
        var settings = TextToSpeechProviderSettingsFactory.forProvider(settingsRepo, ElevenLabsTextToSpeechProvider.ID);
        settings.saveModel(TextToSpeechCatalogItem.of("old-model", "Old Model"));
        settings.saveVoice(TextToSpeechCatalogItem.of("old-voice", "Old Voice"));
        settingsRepo.put("chat4j.tts.catalog.elevenlabs.modelsFile", "unsafe.json");
        settingsRepo.put("chat4j.tts.catalog.elevenlabs.voicesFile", "unsafe.json");
        settingsRepo.put("chat4j.tts.catalog.elevenlabs.updatedAt", "2026-07-11T00:00:00Z");
        var subject = new TextToSpeechCatalogStore(settingsRepo);

        subject.invalidate(ElevenLabsTextToSpeechProvider.ID);

        assertThat(settingsRepo.get("chat4j.tts.catalog.elevenlabs.modelsFile")).isEmpty();
        assertThat(settingsRepo.get("chat4j.tts.catalog.elevenlabs.voicesFile")).isEmpty();
        assertThat(settingsRepo.get("chat4j.tts.catalog.elevenlabs.updatedAt")).isEmpty();
        assertThat(settingsRepo.get("chat4j.tts.elevenlabs.model.id")).isEmpty();
        assertThat(settingsRepo.get("chat4j.tts.elevenlabs.voice.id")).isEmpty();
    }

    @Test
    @DisplayName("A malformed TTS slot invalidates the correlated models and voices snapshot")
    void catalogs_whenOneSnapshotSlotIsMalformed_fallsBackForBothSlots() throws Exception {
        Path directory = Files.createDirectory(tempDir.resolve("correlated-catalog"));
        var settingsRepo = new SettingsRepository(directory.resolve("settings.properties"));
        Path cacheDirectory = Files.createDirectory(directory.resolve("cache"));
        String modelsReference = "tts-elevenlabs-models-11111111111111111111111111111111.json";
        String voicesReference = "tts-elevenlabs-voices-22222222222222222222222222222222.json";
        Files.writeString(
                cacheDirectory.resolve(modelsReference),
                "[{\"id\":\"cached-model\",\"label\":\"Cached Model\",\"description\":\"\"}]"
        );
        Files.writeString(cacheDirectory.resolve(voicesReference), "not json");
        settingsRepo.put("chat4j.tts.catalog.elevenlabs.modelsFile", modelsReference);
        settingsRepo.put("chat4j.tts.catalog.elevenlabs.voicesFile", voicesReference);
        var provider = new ElevenLabsTextToSpeechProvider(request -> new TtsHttpResponse(200, null, new byte[0]));
        var subject = new TextToSpeechCatalogStore(settingsRepo);

        var catalogs = subject.catalogs(provider, provider.defaultModel(), provider.defaultVoice());

        assertThat(catalogs.models()).extracting(TextToSpeechCatalogItem::id).doesNotContain("cached-model");
        assertThat(catalogs.models()).contains(provider.defaultModel());
        assertThat(catalogs.voices()).contains(provider.defaultVoice());
    }

    @Test
    @DisplayName("Malformed cached catalog falls back to bundled values")
    void models_malformedJson_usesBundledValues() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-catalog", ".properties"));
        settingsRepo.put("chat4j.tts.catalog.groq.modelsFile", "not json");
        var provider = new GroqTextToSpeechProvider(request -> new TtsHttpResponse(200, null, new byte[0]));
        var subject = new TextToSpeechCatalogStore(settingsRepo);

        var models = subject.models(provider, provider.defaultModel());

        assertThat(models).extracting(TextToSpeechCatalogItem::id).contains("canopylabs/orpheus-v1-english");
    }
}
