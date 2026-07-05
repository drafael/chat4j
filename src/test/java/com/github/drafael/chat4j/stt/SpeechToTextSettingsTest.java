package com.github.drafael.chat4j.stt;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
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
