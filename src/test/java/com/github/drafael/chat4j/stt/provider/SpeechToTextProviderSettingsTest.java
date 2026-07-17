package com.github.drafael.chat4j.stt.provider;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.provider.assemblyai.AssemblyAiSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.deepgram.DeepgramSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.elevenlabs.ElevenLabsSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.groq.GroqSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.groq.GroqSpeechToTextSettings;
import com.github.drafael.chat4j.stt.provider.vosk.VoskInstalledModel;
import com.github.drafael.chat4j.stt.provider.vosk.VoskSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.vosk.VoskSpeechToTextSettings;
import com.github.drafael.chat4j.stt.provider.vosk.VoskValidationStatus;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperInstalledModel;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperSpeechToTextSettings;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperValidationStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class SpeechToTextProviderSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Known STT provider settings preserve selected model keys")
    void knownProviderSettings_whenSaved_useSelectedModelKeys() {
        var repo = repo("known.properties");
        var subject = new GroqSpeechToTextSettings(repo);
        var fallback = new SpeechToTextCatalogItem("fallback-model", "Fallback Model", "fallback description");

        subject.saveModel(SpeechToTextCatalogItem.of("whisper-large-v3-turbo", "Whisper Turbo"));

        assertThat(repo.get("chat4j.stt.groq.model.id")).contains("whisper-large-v3-turbo");
        assertThat(repo.get("chat4j.stt.groq.model.label")).contains("Whisper Turbo");
        assertThat(subject.selectedModel(fallback).description()).isEqualTo("fallback description");
    }

    @Test
    @DisplayName("Clearing an STT model removes both saved selection keys")
    void clearModel_whenSelectionSaved_removesIdAndLabel() {
        var repo = repo("clear-model.properties");
        var subject = new GroqSpeechToTextSettings(repo);
        subject.saveModel(SpeechToTextCatalogItem.of("saved-model", "Saved Model"));

        subject.clearModelIf(() -> true);

        assertThat(repo.get("chat4j.stt.groq.model.id")).isEmpty();
        assertThat(repo.get("chat4j.stt.groq.model.label")).isEmpty();
    }

    @Test
    @DisplayName("Conditional STT selection writes leave settings unchanged when rejected")
    void saveModelIf_whenConditionRejected_preservesSelection() {
        var repo = repo("conditional-model.properties");
        var subject = new GroqSpeechToTextSettings(repo);
        subject.saveModel(SpeechToTextCatalogItem.of("saved-model", "Saved Model"));

        boolean modelSaved = subject.saveModelIf(
                SpeechToTextCatalogItem.of("replacement-model", "Replacement Model"),
                () -> false
        );
        boolean modelCleared = subject.clearModelIf(() -> false);

        assertThat(modelSaved).isFalse();
        assertThat(modelCleared).isFalse();
        assertThat(repo.get("chat4j.stt.groq.model.id")).contains("saved-model");
        assertThat(repo.get("chat4j.stt.groq.model.label")).contains("Saved Model");
    }

    @Test
    @DisplayName("Blank STT selected model id falls back to provider default")
    void selectedModel_whenSavedIdBlank_returnsFallbackId() {
        var repo = repo("blank-model.properties");
        repo.put("chat4j.stt.groq.model.id", " ");
        repo.put("chat4j.stt.groq.model.label", "Saved Label");
        var subject = new GroqSpeechToTextSettings(repo);

        var selected = subject.selectedModel(SpeechToTextCatalogItem.of("default-model", "Default Model"));

        assertThat(selected.id()).isEqualTo("default-model");
        assertThat(selected.label()).isEqualTo("Saved Label");
    }

    @Test
    @DisplayName("STT selected model returns null when provider has no fallback model")
    void selectedModel_whenFallbackNull_returnsNull() {
        var subject = new GroqSpeechToTextSettings(repo("null-fallback.properties"));

        assertThat(subject.selectedModel(null)).isNull();
    }

    @Test
    @DisplayName("STT settings factory returns concrete settings for known providers")
    void forProvider_whenKnownProviderIdCaseVaries_returnsConcreteSettings() {
        var repo = repo("factory.properties");

        assertThat(SpeechToTextProviderSettingsFactory.forProvider(repo, GroqSpeechToTextProvider.ID.toUpperCase()))
                .isInstanceOf(GroqSpeechToTextSettings.class);
        assertThat(SpeechToTextProviderSettingsFactory.forProvider(repo, ElevenLabsSpeechToTextProvider.ID).providerId())
                .isEqualTo(ElevenLabsSpeechToTextProvider.ID);
        assertThat(SpeechToTextProviderSettingsFactory.forProvider(repo, DeepgramSpeechToTextProvider.ID).providerId())
                .isEqualTo(DeepgramSpeechToTextProvider.ID);
        assertThat(SpeechToTextProviderSettingsFactory.forProvider(repo, AssemblyAiSpeechToTextProvider.ID).providerId())
                .isEqualTo(AssemblyAiSpeechToTextProvider.ID);
        assertThat(SpeechToTextProviderSettingsFactory.forProvider(repo, WhisperSpeechToTextProvider.ID).providerId())
                .isEqualTo(WhisperSpeechToTextProvider.ID);
        assertThat(SpeechToTextProviderSettingsFactory.forProvider(repo, VoskSpeechToTextProvider.ID).providerId())
                .isEqualTo(VoskSpeechToTextProvider.ID);
    }

    @Test
    @DisplayName("Fallback STT provider settings preserve legacy provider slug behavior")
    void fallbackSettings_whenProviderUnknown_preserveLegacySlugKeys() {
        var repo = repo("fallback.properties");
        var mixedCase = SpeechToTextProviderSettingsFactory.forProvider(repo, "Custom Provider!!");
        var blank = SpeechToTextProviderSettingsFactory.forProvider(repo, null);

        mixedCase.saveModel(SpeechToTextCatalogItem.of("custom-model", "Custom Model"));
        blank.saveModel(SpeechToTextCatalogItem.of("blank-model", "Blank Model"));

        assertThat(repo.get("chat4j.stt.custom-provider.model.id")).contains("custom-model");
        assertThat(repo.get("chat4j.stt.unknown.model.id")).contains("blank-model");
    }

    @Test
    @DisplayName("Vosk settings preserve selected model state")
    void voskSettings_whenSavingLocalState_preservesSelectedModelState() {
        var repo = repo("vosk.properties");
        var subject = new VoskSpeechToTextSettings(repo);
        var model = new VoskInstalledModel(
                "local:custom",
                "Custom",
                tempDir.resolve("model"),
                null,
                null,
                true,
                false,
                true,
                VoskValidationStatus.VALID,
                "ready",
                "fingerprint-1"
        );

        subject.saveSelectedModel(model, tempDir.resolve("models").resolve("vosk"));

        assertThat(repo.get("chat4j.stt.vosk.model.id")).contains("local:custom");
        assertThat(repo.get("chat4j.stt.vosk.model.label")).contains("Custom");
        assertThat(subject.savedRoot()).isEqualTo(tempDir.resolve("models").resolve("vosk").toString());
        assertThat(subject.savedFingerprint()).isEqualTo("fingerprint-1");

        subject.clearSelectedModel();

        assertThat(repo.get("chat4j.stt.vosk.model.id")).isEmpty();
        assertThat(repo.get("chat4j.stt.vosk.model.fingerprint")).isEmpty();
        assertThat(repo.get("chat4j.stt.vosk.model.root")).isEmpty();
    }

    @Test
    @DisplayName("Whisper settings preserve selected model root and fingerprint keys")
    void whisperSettings_whenSavingLocalState_preserveLegacyKeys() {
        var repo = repo("whisper.properties");
        var subject = new WhisperSpeechToTextSettings(repo);
        var model = new WhisperInstalledModel(
                "tiny.en",
                "Tiny English",
                tempDir.resolve("tiny.en"),
                null,
                null,
                true,
                WhisperValidationStatus.VALID,
                "ready",
                "fingerprint-2"
        );

        subject.saveSelectedModel(model, tempDir.resolve("models").resolve("whisper"));

        assertThat(repo.get("chat4j.stt.whisper.model.id")).contains("tiny.en");
        assertThat(repo.get("chat4j.stt.whisper.model.label")).contains("Tiny English");
        assertThat(subject.savedRoot()).isEqualTo(tempDir.resolve("models").resolve("whisper").toString());
        assertThat(subject.savedFingerprint()).isEqualTo("fingerprint-2");

        subject.clearSelectedModel();

        assertThat(repo.get("chat4j.stt.whisper.model.id")).isEmpty();
        assertThat(repo.get("chat4j.stt.whisper.model.fingerprint")).isEmpty();
        assertThat(repo.get("chat4j.stt.whisper.model.root")).isEmpty();
    }

    private SettingsRepository repo(String fileName) {
        return new SettingsRepository(tempDir.resolve(fileName));
    }
}
