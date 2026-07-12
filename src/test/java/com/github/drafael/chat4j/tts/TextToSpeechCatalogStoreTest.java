package com.github.drafael.chat4j.tts;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogStore;
import com.github.drafael.chat4j.tts.provider.TextToSpeechProviderSettingsFactory;
import com.github.drafael.chat4j.tts.provider.TtsHttpResponse;
import com.github.drafael.chat4j.tts.provider.elevenlabs.ElevenLabsTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.groq.GroqTextToSpeechProvider;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextToSpeechCatalogStoreTest {

    @Test
    @DisplayName("Saved catalogs use legacy persisted keys")
    void saveCatalogs_whenElevenLabsProvider_writesLegacyKeysAndReadsCatalogs() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-catalog", ".properties"));
        var provider = new ElevenLabsTextToSpeechProvider(request -> new TtsHttpResponse(200, null, new byte[0]));
        var subject = new TextToSpeechCatalogStore(settingsRepo);

        subject.saveCatalogs(
                ElevenLabsTextToSpeechProvider.ID,
                List.of(TextToSpeechCatalogItem.of("eleven_turbo_v2_5", "Eleven Turbo")),
                List.of(TextToSpeechCatalogItem.of("voice-a", "Voice A"))
        );

        assertThat(settingsRepo.get("chat4j.tts.catalog.elevenlabs.models"))
                .hasValueSatisfying(json -> assertThat(json).contains("eleven_turbo_v2_5"));
        assertThat(settingsRepo.get("chat4j.tts.catalog.elevenlabs.voices"))
                .hasValueSatisfying(json -> assertThat(json).contains("voice-a"));
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
        settingsRepo.put(
                "chat4j.tts.catalog.elevenlabs.voices",
                "[{\"id\":\"voice-a\",\"label\":\"Voice A\",\"description\":\"\"}]"
        );
        var provider = new ElevenLabsTextToSpeechProvider(request -> new TtsHttpResponse(200, null, new byte[0]));
        var subject = new TextToSpeechCatalogStore(settingsRepo);

        var voices = subject.voices(provider, TextToSpeechCatalogItem.of("saved-voice", "Saved Voice"));

        assertThat(voices).extracting(TextToSpeechCatalogItem::id).containsExactly("voice-a", "saved-voice");
    }

    @Test
    @DisplayName("Cached catalog ignores null entries")
    void voices_cachedCatalogContainsNull_ignoresNullEntry() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-catalog", ".properties"));
        settingsRepo.put(
                "chat4j.tts.catalog.elevenlabs.voices",
                "[null,{\"id\":\"voice-a\",\"label\":\"Voice A\",\"description\":\"\"}]"
        );
        var provider = new ElevenLabsTextToSpeechProvider(request -> new TtsHttpResponse(200, null, new byte[0]));
        var subject = new TextToSpeechCatalogStore(settingsRepo);

        var voices = subject.voices(provider, provider.defaultVoice());

        assertThat(voices).extracting(TextToSpeechCatalogItem::id).contains("voice-a");
    }

    @Test
    @DisplayName("Invalidating TTS catalog clears stale selected model and voice")
    void invalidate_whenProviderHasSelections_removesTimestampAndSelections() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-invalidate", ".properties"));
        var settings = TextToSpeechProviderSettingsFactory.forProvider(settingsRepo, ElevenLabsTextToSpeechProvider.ID);
        settings.saveModel(TextToSpeechCatalogItem.of("old-model", "Old Model"));
        settings.saveVoice(TextToSpeechCatalogItem.of("old-voice", "Old Voice"));
        settingsRepo.put("chat4j.tts.catalog.elevenlabs.models", "[{\"id\":\"old-model\",\"label\":\"Old Model\",\"description\":\"\"}]");
        settingsRepo.put("chat4j.tts.catalog.elevenlabs.voices", "[{\"id\":\"old-voice\",\"label\":\"Old Voice\",\"description\":\"\"}]");
        settingsRepo.put("chat4j.tts.catalog.elevenlabs.updatedAt", "2026-07-11T00:00:00Z");
        var subject = new TextToSpeechCatalogStore(settingsRepo);

        subject.invalidate(ElevenLabsTextToSpeechProvider.ID);

        assertThat(settingsRepo.get("chat4j.tts.catalog.elevenlabs.models")).isEmpty();
        assertThat(settingsRepo.get("chat4j.tts.catalog.elevenlabs.voices")).isEmpty();
        assertThat(settingsRepo.get("chat4j.tts.catalog.elevenlabs.updatedAt")).isEmpty();
        assertThat(settingsRepo.get("chat4j.tts.elevenlabs.model.id")).isEmpty();
        assertThat(settingsRepo.get("chat4j.tts.elevenlabs.voice.id")).isEmpty();
    }

    @Test
    @DisplayName("Malformed cached catalog falls back to bundled values")
    void models_malformedJson_usesBundledValues() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-catalog", ".properties"));
        settingsRepo.put("chat4j.tts.catalog.groq.models", "not json");
        var provider = new GroqTextToSpeechProvider(request -> new TtsHttpResponse(200, null, new byte[0]));
        var subject = new TextToSpeechCatalogStore(settingsRepo);

        var models = subject.models(provider, provider.defaultModel());

        assertThat(models).extracting(TextToSpeechCatalogItem::id).contains("canopylabs/orpheus-v1-english");
    }
}
