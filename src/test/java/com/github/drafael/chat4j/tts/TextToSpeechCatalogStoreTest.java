package com.github.drafael.chat4j.tts;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Files;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextToSpeechCatalogStoreTest {

    @Test
    @DisplayName("Saved selection is included when absent from cached catalog")
    void voices_savedSelectionAbsent_includesSavedSelection() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-catalog", ".properties"));
        settingsRepo.put(
                SettingsKeys.ttsCatalogVoicesKey(ElevenLabsTextToSpeechProvider.ID),
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
                SettingsKeys.ttsCatalogVoicesKey(ElevenLabsTextToSpeechProvider.ID),
                "[null,{\"id\":\"voice-a\",\"label\":\"Voice A\",\"description\":\"\"}]"
        );
        var provider = new ElevenLabsTextToSpeechProvider(request -> new TtsHttpResponse(200, null, new byte[0]));
        var subject = new TextToSpeechCatalogStore(settingsRepo);

        var voices = subject.voices(provider, provider.defaultVoice());

        assertThat(voices).extracting(TextToSpeechCatalogItem::id).contains("voice-a");
    }

    @Test
    @DisplayName("Malformed cached catalog falls back to bundled values")
    void models_malformedJson_usesBundledValues() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-catalog", ".properties"));
        settingsRepo.put(SettingsKeys.ttsCatalogModelsKey(GroqTextToSpeechProvider.ID), "not json");
        var provider = new GroqTextToSpeechProvider(request -> new TtsHttpResponse(200, null, new byte[0]));
        var subject = new TextToSpeechCatalogStore(settingsRepo);

        var models = subject.models(provider, provider.defaultModel());

        assertThat(models).extracting(TextToSpeechCatalogItem::id).contains("canopylabs/orpheus-v1-english");
    }
}
