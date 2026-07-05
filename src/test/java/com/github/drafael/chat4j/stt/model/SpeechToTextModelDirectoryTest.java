package com.github.drafael.chat4j.stt.model;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpeechToTextModelDirectoryTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Default model directory comes from injected path")
    void resolve_whenNoOverride_usesInjectedDefault() {
        var subject = new SpeechToTextModelDirectory(new SettingsRepository(tempDir.resolve("settings.properties")), tempDir.resolve("models"));

        assertThat(subject.resolve()).isEqualTo(tempDir.resolve("models").toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("Saved model directory is normalized and created")
    void saveAndCreate_whenRelativePath_persistsNormalizedPath() throws Exception {
        Path settingsFile = tempDir.resolve("settings.properties");
        var repo = new SettingsRepository(settingsFile);
        var subject = new SpeechToTextModelDirectory(repo, tempDir.resolve("models"));

        Path saved = subject.saveAndCreate(tempDir.resolve("nested/../stt").toString());

        assertThat(Files.isDirectory(saved)).isTrue();
        assertThat(repo.get(SettingsKeys.STT_MODELS_DIR)).contains(saved.toString());
    }

    @Test
    @DisplayName("Existing file path is rejected with a clear model directory message")
    void saveAndCreate_whenPathIsFile_rejectsWithClearMessage() throws Exception {
        var subject = new SpeechToTextModelDirectory(new SettingsRepository(tempDir.resolve("settings.properties")), tempDir.resolve("models"));
        Path file = Files.writeString(tempDir.resolve("model-file"), "content");

        assertThatThrownBy(() -> subject.saveAndCreate(file.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a folder");
    }

    @Test
    @DisplayName("Model directory slugs provider and model identifiers")
    void directoryFor_whenIdentifiersContainSpaces_slugsPathSegments() {
        var subject = new SpeechToTextModelDirectory(new SettingsRepository(tempDir.resolve("settings.properties")), tempDir.resolve("models"));

        assertThat(subject.directoryFor("Groq Cloud", "Whisper/Large v3").toString())
                .endsWith(Path.of("groq-cloud", "whisper-large-v3").toString());
    }
}
