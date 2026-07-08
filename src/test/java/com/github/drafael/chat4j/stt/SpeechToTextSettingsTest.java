package com.github.drafael.chat4j.stt;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.stt.provider.CredentialSource;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpeechToTextSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Speech to Text defaults to off even when Groq credentials are available")
    void resolve_whenProviderMissing_defaultsOff() {
        var subject = new SpeechToTextSettings(
                new SettingsRepository(tempDir.resolve("settings.properties")),
                SpeechToTextProviderRegistry.createDefault(),
                availableCredentials(),
                tempDir.resolve("stt").resolve("models")
        );

        assertThat(subject.resolve().enabled()).isFalse();
        assertThat(subject.resolve().providerId()).isEqualTo(SettingsKeys.STT_PROVIDER_OFF);
    }

    @Test
    @DisplayName("Saved Groq remains selected but unavailable without credentials")
    void resolve_whenGroqMissingCredentials_remainsSelectedUnavailable() {
        var repo = new SettingsRepository(tempDir.resolve("settings.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_GROQ);
        var subject = new SpeechToTextSettings(repo, SpeechToTextProviderRegistry.createDefault(), missingCredentials(), tempDir.resolve("stt").resolve("models"));

        var snapshot = subject.resolve();

        assertThat(snapshot.providerId()).isEqualTo(SettingsKeys.STT_PROVIDER_GROQ);
        assertThat(snapshot.enabled()).isTrue();
        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.statusMessage()).contains("GROQ_API_KEY");
    }

    @Test
    @DisplayName("Blank persisted Groq model id falls back to provider default")
    void resolve_whenPersistedModelIdBlank_usesDefaultModel() {
        var repo = new SettingsRepository(tempDir.resolve("settings.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_GROQ);
        repo.put(SettingsKeys.sttModelIdKey(SettingsKeys.STT_PROVIDER_GROQ), " ");
        var subject = new SpeechToTextSettings(repo, SpeechToTextProviderRegistry.createDefault(), availableCredentials(), tempDir.resolve("stt").resolve("models"));

        assertThat(subject.resolve().model().id()).isEqualTo("whisper-large-v3-turbo");
    }

    @Test
    @DisplayName("ElevenLabs resolves official endpoints and default model")
    void resolve_whenElevenLabsSelected_usesElevenLabsEndpointsAndDefaultModel() {
        var repo = new SettingsRepository(tempDir.resolve("settings-elevenlabs.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_ELEVENLABS);
        var subject = new SpeechToTextSettings(repo, SpeechToTextProviderRegistry.createDefault(), availableCredentials(), tempDir.resolve("stt").resolve("models"));

        var snapshot = subject.resolve();

        assertThat(snapshot.providerId()).isEqualTo(SettingsKeys.STT_PROVIDER_ELEVENLABS);
        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.model().id()).isEqualTo("scribe_v2");
        assertThat(snapshot.baseUri()).hasToString("https://api.elevenlabs.io");
        assertThat(snapshot.transcriptionUri()).hasToString("https://api.elevenlabs.io/v1/speech-to-text");
    }

    @Test
    @DisplayName("Saved ElevenLabs remains selected but unavailable without credentials")
    void resolve_whenElevenLabsMissingCredentials_remainsSelectedUnavailable() {
        var repo = new SettingsRepository(tempDir.resolve("settings-elevenlabs-missing.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_ELEVENLABS);
        var subject = new SpeechToTextSettings(repo, SpeechToTextProviderRegistry.createDefault(), missingCredentials(), tempDir.resolve("stt").resolve("models"));

        var snapshot = subject.resolve();

        assertThat(snapshot.providerId()).isEqualTo(SettingsKeys.STT_PROVIDER_ELEVENLABS);
        assertThat(snapshot.enabled()).isTrue();
        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.statusMessage()).contains("ELEVENLABS_API_KEY");
    }

    @Test
    @DisplayName("Deepgram resolves official endpoints and default model")
    void resolve_whenDeepgramSelected_usesDeepgramEndpointsAndDefaultModel() {
        var repo = new SettingsRepository(tempDir.resolve("settings-deepgram.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_DEEPGRAM);
        var subject = new SpeechToTextSettings(repo, SpeechToTextProviderRegistry.createDefault(), availableCredentials(), tempDir.resolve("stt").resolve("models"));

        var snapshot = subject.resolve();

        assertThat(snapshot.providerId()).isEqualTo(SettingsKeys.STT_PROVIDER_DEEPGRAM);
        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.model().id()).isEqualTo("nova-3");
        assertThat(snapshot.baseUri()).hasToString("https://api.deepgram.com");
        assertThat(snapshot.transcriptionUri()).hasToString("https://api.deepgram.com/v1/listen");
    }

    @Test
    @DisplayName("Saved Deepgram remains selected but unavailable without credentials")
    void resolve_whenDeepgramMissingCredentials_remainsSelectedUnavailable() {
        var repo = new SettingsRepository(tempDir.resolve("settings-deepgram-missing.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_DEEPGRAM);
        var subject = new SpeechToTextSettings(repo, SpeechToTextProviderRegistry.createDefault(), missingCredentials(), tempDir.resolve("stt").resolve("models"));

        var snapshot = subject.resolve();

        assertThat(snapshot.providerId()).isEqualTo(SettingsKeys.STT_PROVIDER_DEEPGRAM);
        assertThat(snapshot.enabled()).isTrue();
        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.statusMessage()).contains("DEEPGRAM_API_KEY");
    }

    @Test
    @DisplayName("AssemblyAI resolves official endpoints and default automatic model")
    void resolve_whenAssemblyAiSelected_usesAssemblyAiEndpointsAndDefaultModel() {
        var repo = new SettingsRepository(tempDir.resolve("settings-assemblyai.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_ASSEMBLYAI);
        var subject = new SpeechToTextSettings(repo, SpeechToTextProviderRegistry.createDefault(), availableCredentials(), tempDir.resolve("stt").resolve("models"));

        var snapshot = subject.resolve();

        assertThat(snapshot.providerId()).isEqualTo(SettingsKeys.STT_PROVIDER_ASSEMBLYAI);
        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.model().id()).isEqualTo("assemblyai-auto");
        assertThat(snapshot.baseUri()).hasToString("https://api.assemblyai.com");
        assertThat(snapshot.transcriptionUri()).hasToString("https://api.assemblyai.com/v2/transcript");
    }

    @Test
    @DisplayName("Saved AssemblyAI remains selected but unavailable without credentials")
    void resolve_whenAssemblyAiMissingCredentials_remainsSelectedUnavailable() {
        var repo = new SettingsRepository(tempDir.resolve("settings-assemblyai-missing.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_ASSEMBLYAI);
        var subject = new SpeechToTextSettings(repo, SpeechToTextProviderRegistry.createDefault(), missingCredentials(), tempDir.resolve("stt").resolve("models"));

        var snapshot = subject.resolve();

        assertThat(snapshot.providerId()).isEqualTo(SettingsKeys.STT_PROVIDER_ASSEMBLYAI);
        assertThat(snapshot.enabled()).isTrue();
        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.statusMessage()).contains("ASSEMBLYAI_API_KEY");
    }

    @Test
    @DisplayName("Unknown persisted AssemblyAI model falls back to automatic model")
    void resolve_whenAssemblyAiPersistedModelUnknown_usesDefaultModel() {
        var repo = new SettingsRepository(tempDir.resolve("settings-assemblyai-model.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_ASSEMBLYAI);
        repo.put(SettingsKeys.sttModelIdKey(SettingsKeys.STT_PROVIDER_ASSEMBLYAI), "stale-model");
        var subject = new SpeechToTextSettings(repo, SpeechToTextProviderRegistry.createDefault(), availableCredentials(), tempDir.resolve("stt").resolve("models"));

        assertThat(subject.resolve().model().id()).isEqualTo("assemblyai-auto");
    }

    @Test
    @DisplayName("Credential resolver includes STT API keys")
    void supportedProviderEnvVars_whenQueried_includesSttProviders() {
        assertThat(CredentialResolver.supportedProviderEnvVars()).contains("DEEPGRAM_API_KEY", "ASSEMBLYAI_API_KEY");
    }

    @Test
    @DisplayName("Invalid persisted max duration resolves to default")
    void resolveMaxDurationSeconds_whenPersistedInvalid_returnsDefault() {
        var repo = new SettingsRepository(tempDir.resolve("settings.properties"));
        repo.put(SettingsKeys.STT_RECORDING_MAX_DURATION_SECONDS, "900");
        var subject = new SpeechToTextSettings(repo, SpeechToTextProviderRegistry.createDefault(), missingCredentials(), tempDir.resolve("stt").resolve("models"));

        assertThat(subject.resolveMaxDurationSeconds()).isEqualTo(600);
    }

    @Test
    @DisplayName("Max duration validation rejects out-of-range values")
    void validateMaxDurationSeconds_whenOutOfRange_rejects() {
        assertThatThrownBy(() -> SpeechToTextSettings.validateMaxDurationSeconds(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SpeechToTextSettings.validateMaxDurationSeconds(601))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private CredentialSource availableCredentials() {
        return new CredentialSource() {
            @Override
            public boolean hasRequiredCredentials(String envVar) {
                return true;
            }

            @Override
            public String resolveRequiredApiKey(String envVar) {
                return "test-key";
            }
        };
    }

    private CredentialSource missingCredentials() {
        return new CredentialSource() {
            @Override
            public boolean hasRequiredCredentials(String envVar) {
                return false;
            }

            @Override
            public String resolveRequiredApiKey(String envVar) {
                return "";
            }
        };
    }
}
