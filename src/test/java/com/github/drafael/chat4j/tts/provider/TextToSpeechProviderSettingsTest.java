package com.github.drafael.chat4j.tts.provider;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.tts.provider.deepgram.DeepgramTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.deepgram.DeepgramTextToSpeechSettings;
import com.github.drafael.chat4j.tts.provider.elevenlabs.ElevenLabsTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.groq.GroqTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.groq.GroqTextToSpeechSettings;
import com.github.drafael.chat4j.tts.provider.system.SystemTextToSpeechProvider;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class TextToSpeechProviderSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Known TTS provider settings preserve selected model and voice keys")
    void knownProviderSettings_whenSaved_useSelectionKeys() {
        var repo = repo("known.properties");
        var subject = new GroqTextToSpeechSettings(repo);
        var fallbackModel = new TextToSpeechCatalogItem("fallback-model", "Fallback Model", "fallback description");
        var fallbackVoice = new TextToSpeechCatalogItem("fallback-voice", "Fallback Voice", "voice description");

        subject.saveModel(TextToSpeechCatalogItem.of("playai-tts", "Legacy Groq Model"));
        subject.saveVoice(TextToSpeechCatalogItem.of("Arista-PlayAI", "Arista"));

        assertThat(repo.get("chat4j.tts.groq.model.id")).contains("playai-tts");
        assertThat(repo.get("chat4j.tts.groq.model.label")).contains("Legacy Groq Model");
        assertThat(repo.get("chat4j.tts.groq.voice.id")).contains("Arista-PlayAI");
        assertThat(repo.get("chat4j.tts.groq.voice.label")).contains("Arista");
        assertThat(subject.selectedModel(fallbackModel).description()).isEqualTo("fallback description");
        assertThat(subject.selectedVoice(fallbackVoice).description()).isEqualTo("voice description");
    }

    @Test
    @DisplayName("Deepgram TTS keeps model-family and voice keys separate")
    void deepgramSettings_whenModelAndVoiceSaved_writesSeparateModelAndVoiceKeys() {
        var repo = repo("deepgram.properties");
        var subject = new DeepgramTextToSpeechSettings(repo);

        subject.saveModel(TextToSpeechCatalogItem.of("aura-2", "Aura 2"));
        subject.saveVoice(TextToSpeechCatalogItem.of("aura-2-thalia-en", "thalia"));

        assertThat(repo.get("chat4j.tts.deepgram.model.id")).contains("aura-2");
        assertThat(repo.get("chat4j.tts.deepgram.voice.id")).contains("aura-2-thalia-en");
        assertThat(repo.get("chat4j.tts.deepgram.model.label")).contains("Aura 2");
        assertThat(repo.get("chat4j.tts.deepgram.voice.label")).contains("thalia");
    }

    @Test
    @DisplayName("TTS settings factory returns concrete settings for known providers")
    void forProvider_whenKnownProviderIdCaseVaries_returnsConcreteSettings() {
        var repo = repo("factory.properties");

        assertThat(TextToSpeechProviderSettingsFactory.forProvider(repo, GroqTextToSpeechProvider.ID.toUpperCase()))
                .isInstanceOf(GroqTextToSpeechSettings.class);
        assertThat(TextToSpeechProviderSettingsFactory.forProvider(repo, ElevenLabsTextToSpeechProvider.ID).providerId())
                .isEqualTo(ElevenLabsTextToSpeechProvider.ID);
        assertThat(TextToSpeechProviderSettingsFactory.forProvider(repo, SystemTextToSpeechProvider.ID).providerId())
                .isEqualTo(SystemTextToSpeechProvider.ID);
    }

    @Test
    @DisplayName("Fallback TTS provider settings preserve legacy provider slug behavior")
    void fallbackSettings_whenProviderUnknown_preserveLegacySlugKeys() {
        var repo = repo("fallback.properties");
        var mixedCase = TextToSpeechProviderSettingsFactory.forProvider(repo, "Custom Provider!!");
        var blank = TextToSpeechProviderSettingsFactory.forProvider(repo, " ");

        mixedCase.saveModel(TextToSpeechCatalogItem.of("custom-model", "Custom Model"));
        blank.saveVoice(TextToSpeechCatalogItem.of("custom-voice", "Custom Voice"));

        assertThat(repo.get("chat4j.tts.custom-provider.model.id")).contains("custom-model");
        assertThat(repo.get("chat4j.tts.unknown.voice.id")).contains("custom-voice");
    }

    private SettingsRepository repo(String fileName) {
        return new SettingsRepository(tempDir.resolve(fileName));
    }
}
