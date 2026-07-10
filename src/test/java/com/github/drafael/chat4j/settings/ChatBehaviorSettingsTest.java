package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatBehaviorSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Chat behavior settings return defaults when settings are missing")
    void read_whenSettingsMissing_returnsDefaults() {
        var subject = new ChatBehaviorSettings(settingsRepo("chat-behavior-defaults"));

        assertThat(subject.sendKey()).isEqualTo("Enter");
        assertThat(subject.sendOnEnter()).isTrue();
        assertThat(subject.autoScrollEnabled()).isTrue();
        assertThat(subject.menuBarEnabled(false)).isFalse();
        assertThat(subject.menuBarEnabled(true)).isTrue();
    }

    @Test
    @DisplayName("Chat behavior settings return persisted values")
    void read_whenSettingsPersisted_returnsPersistedValues() throws Exception {
        var settingsRepo = settingsRepo("chat-behavior-persisted");
        settingsRepo.put("chat4j.chat.input.sendKey", "Ctrl+Enter");
        settingsRepo.put("chat4j.chat.behavior.autoScroll", "false");
        settingsRepo.put("chat4j.ui.menuBar.enabled", "false");
        var subject = new ChatBehaviorSettings(settingsRepo);

        assertThat(subject.sendKey()).isEqualTo("Ctrl+Enter");
        assertThat(subject.sendOnEnter()).isFalse();
        assertThat(subject.autoScrollEnabled()).isFalse();
        assertThat(subject.menuBarEnabled(true)).isFalse();
    }

    @Test
    @DisplayName("Only Ctrl+Enter disables send-on-enter")
    void sendOnEnter_whenUnexpectedValueStored_returnsTrue() throws Exception {
        var settingsRepo = settingsRepo("chat-behavior-send-key-unexpected");
        settingsRepo.put("chat4j.chat.input.sendKey", "Space");
        var subject = new ChatBehaviorSettings(settingsRepo);

        assertThat(subject.sendOnEnter()).isTrue();
    }

    @Test
    @DisplayName("Chat behavior settings persist unchanged key names")
    void persist_whenCalled_writesPersistedSettingNames() throws Exception {
        var settingsRepo = settingsRepo("chat-behavior-persist");
        var subject = new ChatBehaviorSettings(settingsRepo);

        subject.persistSendKey("Ctrl+Enter");
        subject.persistAutoScrollEnabled(false);
        subject.persistMenuBarEnabled(false);

        assertThat(settingsRepo.get("chat4j.chat.input.sendKey")).contains("Ctrl+Enter");
        assertThat(settingsRepo.get("chat4j.chat.behavior.autoScroll")).contains("false");
        assertThat(settingsRepo.get("chat4j.ui.menuBar.enabled")).contains("false");
    }

    @Test
    @DisplayName("Read failures propagate")
    void read_whenRepositoryFails_propagatesFailure() {
        var subject = new ChatBehaviorSettings(new ThrowingSettingsRepo(true));

        assertThatThrownBy(subject::sendKey)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forced failure");
    }

    @Test
    @DisplayName("Write failures propagate")
    void persist_whenRepositoryFails_propagatesFailure() {
        var subject = new ChatBehaviorSettings(new ThrowingSettingsRepo(false));

        assertThatThrownBy(() -> subject.persistSendKey("Enter"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forced failure");
    }

    private SettingsRepository settingsRepo(String testName) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(testName)));
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private final boolean failReads;

        private ThrowingSettingsRepo(boolean failReads) {
            super(Path.of("unused-chat-behavior-settings.properties"));
            this.failReads = failReads;
        }

        @Override
        public Optional<String> get(String key) {
            if (failReads) {
                throw new IllegalStateException("forced failure");
            }
            return Optional.empty();
        }

        @Override
        public void put(String key, String value) {
            throw new IllegalStateException("forced failure");
        }
    }
}
