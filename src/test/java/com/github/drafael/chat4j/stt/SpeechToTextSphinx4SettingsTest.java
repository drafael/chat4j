package com.github.drafael.chat4j.stt;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.provider.CredentialSource;
import com.github.drafael.chat4j.stt.provider.sphinx4.Sphinx4ModelManagementService;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class SpeechToTextSphinx4SettingsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Sphinx4 stays selectable but unavailable when no model is installed")
    void resolve_whenSphinx4HasNoInstalledModel_isSelectableUnavailable() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_SPHINX4);
        Path models = tempDir.resolve("models");
        var sphinx4Models = new Sphinx4ModelManagementService(repo, models, tempDir.resolve("temp"));
        var subject = new SpeechToTextSettings(
                repo,
                SpeechToTextProviderRegistry.createDefault(),
                credentials(),
                models,
                null,
                sphinx4Models
        );
        sphinx4Models.refreshAsync();
        waitUntil(() -> sphinx4Models.snapshot().catalog().size() == 15);

        var snapshot = subject.resolve();

        assertThat(snapshot.enabled()).isTrue();
        assertThat(snapshot.providerId()).isEqualTo(SettingsKeys.STT_PROVIDER_SPHINX4);
        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.model()).isNull();
        assertThat(snapshot.statusMessage()).contains("Download or add");
        sphinx4Models.close();
    }

    private void waitUntil(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.met() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.met()).isTrue();
    }

    private CredentialSource credentials() {
        return new CredentialSource() {
            @Override
            public boolean hasRequiredCredentials(String envVar) {
                return true;
            }

            @Override
            public String resolveRequiredApiKey(String envVar) {
                return "";
            }
        };
    }

    @FunctionalInterface
    private interface Condition {
        boolean met();
    }
}
