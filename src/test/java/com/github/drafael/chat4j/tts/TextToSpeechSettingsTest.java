package com.github.drafael.chat4j.tts;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class TextToSpeechSettingsTest {

    @Test
    @DisplayName("Provider-specific model and voice selections are preserved independently")
    void resolve_providerSpecificSelections_preservesSelectionsIndependently() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-settings", ".properties"));
        var registry = new TextToSpeechProviderRegistry(List.of(
                new GroqTextToSpeechProvider(request -> new TtsHttpResponse(200, null, new byte[0])),
                new ElevenLabsTextToSpeechProvider(request -> new TtsHttpResponse(200, null, new byte[0]))
        ));
        var subject = new TextToSpeechSettings(settingsRepo, registry);

        subject.saveProvider(GroqTextToSpeechProvider.ID);
        subject.saveModel(GroqTextToSpeechProvider.ID, TextToSpeechCatalogItem.of("playai-tts", "Groq Model"));
        subject.saveVoice(GroqTextToSpeechProvider.ID, TextToSpeechCatalogItem.of("Arista-PlayAI", "Arista"));
        subject.saveProvider(ElevenLabsTextToSpeechProvider.ID);
        subject.saveModel(ElevenLabsTextToSpeechProvider.ID, TextToSpeechCatalogItem.of("eleven_flash_v2_5", "Eleven Model"));
        subject.saveVoice(ElevenLabsTextToSpeechProvider.ID, TextToSpeechCatalogItem.of("voice-1", "Voice One"));

        subject.saveProvider(GroqTextToSpeechProvider.ID);
        TextToSpeechSettings.Selection groq = subject.resolve();
        subject.saveProvider(ElevenLabsTextToSpeechProvider.ID);
        TextToSpeechSettings.Selection elevenLabs = subject.resolve();

        assertThat(groq.model().id()).isEqualTo("canopylabs/orpheus-v1-english");
        assertThat(groq.voice().id()).isEqualTo("hannah");
        assertThat(elevenLabs.model().id()).isEqualTo("eleven_flash_v2_5");
        assertThat(elevenLabs.voice().id()).isEqualTo("voice-1");
        assertThat(settingsRepo.get(SettingsKeys.ttsModelIdKey(GroqTextToSpeechProvider.ID), "")).isEqualTo("playai-tts");
        assertThat(settingsRepo.get(SettingsKeys.ttsModelIdKey(ElevenLabsTextToSpeechProvider.ID), "")).isEqualTo("eleven_flash_v2_5");
    }

    @Test
    @DisplayName("Default provider is off when System is absent")
    void resolve_noProviderSavedAndSystemAbsent_defaultsToOff() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-settings", ".properties"));
        var subject = new TextToSpeechSettings(settingsRepo, new TextToSpeechProviderRegistry(emptyList()));

        TextToSpeechSettings.Selection selection = subject.resolve();

        assertThat(selection.enabled()).isFalse();
        assertThat(selection.providerId()).isEqualTo(SettingsKeys.TTS_PROVIDER_OFF);
    }

    @Test
    @DisplayName("Missing provider setting defaults to available System provider")
    void resolve_noProviderSavedAndSystemAvailable_defaultsToSystem() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-settings", ".properties"));
        var subject = new TextToSpeechSettings(settingsRepo, registryWithSystem(true));

        TextToSpeechSettings.Selection selection = subject.resolve();

        assertThat(selection.enabled()).isTrue();
        assertThat(selection.available()).isTrue();
        assertThat(selection.providerId()).isEqualTo(SettingsKeys.TTS_PROVIDER_SYSTEM);
        assertThat(settingsRepo.get(SettingsKeys.TTS_PROVIDER)).isEmpty();
    }

    @Test
    @DisplayName("Blank provider setting follows the implicit System default")
    void resolve_blankProviderSavedAndSystemAvailable_defaultsToSystem() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-settings", ".properties"));
        settingsRepo.put(SettingsKeys.TTS_PROVIDER, " ");
        var subject = new TextToSpeechSettings(settingsRepo, registryWithSystem(true));

        TextToSpeechSettings.Selection selection = subject.resolve();

        assertThat(selection.providerId()).isEqualTo(SettingsKeys.TTS_PROVIDER_SYSTEM);
    }

    @Test
    @DisplayName("Explicit off is respected when System is available")
    void resolve_offProviderSavedAndSystemAvailable_staysOff() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-settings", ".properties"));
        settingsRepo.put(SettingsKeys.TTS_PROVIDER, SettingsKeys.TTS_PROVIDER_OFF);
        var subject = new TextToSpeechSettings(settingsRepo, registryWithSystem(true));

        TextToSpeechSettings.Selection selection = subject.resolve();

        assertThat(selection.enabled()).isFalse();
        assertThat(selection.providerId()).isEqualTo(SettingsKeys.TTS_PROVIDER_OFF);
    }

    @Test
    @DisplayName("Saved System stays selected when backend becomes unavailable")
    void resolve_systemSavedAndUnavailable_staysSelectedUnavailable() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-settings", ".properties"));
        settingsRepo.put(SettingsKeys.TTS_PROVIDER, SettingsKeys.TTS_PROVIDER_SYSTEM);
        var subject = new TextToSpeechSettings(settingsRepo, registryWithSystem(false));

        TextToSpeechSettings.Selection selection = subject.resolve();

        assertThat(selection.enabled()).isTrue();
        assertThat(selection.available()).isFalse();
        assertThat(selection.providerId()).isEqualTo(SettingsKeys.TTS_PROVIDER_SYSTEM);
    }

    private static TextToSpeechProviderRegistry registryWithSystem(boolean available) {
        return new TextToSpeechProviderRegistry(List.of(new SystemTextToSpeechProvider(new FakeSystemBackend(available))));
    }

    private static final class FakeSystemBackend implements SystemTtsBackend {
        private final boolean available;

        private FakeSystemBackend(boolean available) {
            this.available = available;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String unavailableMessage() {
            return "System unavailable.";
        }

        @Override
        public String availableMessage() {
            return "System available.";
        }

        @Override
        public String defaultResponseFormat() {
            return "wav";
        }

        @Override
        public List<TextToSpeechCatalogItem> fetchVoices() {
            return emptyList();
        }

        @Override
        public TextToSpeechAudio synthesize(TextToSpeechRequest request) {
            return new TextToSpeechAudio(new byte[]{1}, "audio/wav", "wav");
        }
    }
}
