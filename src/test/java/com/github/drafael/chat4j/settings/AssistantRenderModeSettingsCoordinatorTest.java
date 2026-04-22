package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantRenderModeSettingsCoordinatorTest {

    @Test
    @DisplayName("Resolve default mode falls back to preview when setting is missing")
    void resolveDefaultMode_whenSettingMissing_returnsPreview() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("assistant-mode-default-missing");
        var subject = new AssistantRenderModeSettingsCoordinator(settingsRepo);

        assertThat(subject.resolveDefaultMode()).isEqualTo(AssistantRenderMode.PREVIEW);
    }

    @Test
    @DisplayName("Resolve default mode returns markdown when global MARKDOWN value is stored")
    void resolveDefaultMode_whenStoredMarkdownValue_returnsMarkdown() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("assistant-mode-default-markdown");
        settingsRepo.put(SettingsKeys.CHAT_RENDER_MODE, SettingsKeys.CHAT_RENDER_MODE_MARKDOWN);

        var subject = new AssistantRenderModeSettingsCoordinator(settingsRepo);

        assertThat(subject.resolveDefaultMode()).isEqualTo(AssistantRenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Resolve conversation mode returns provided default for null conversation")
    void resolveConversationMode_whenConversationIdIsNull_returnsProvidedDefault() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("assistant-mode-conv-null");
        var subject = new AssistantRenderModeSettingsCoordinator(settingsRepo);

        AssistantRenderMode mode = subject.resolveConversationMode(null, AssistantRenderMode.MARKDOWN);

        assertThat(mode).isEqualTo(AssistantRenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Resolve conversation mode ignores conversation-specific values and returns provided default")
    void resolveConversationMode_whenConversationSpecificValueExists_returnsProvidedDefault() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("assistant-mode-conv-stored");
        UUID conversationId = UUID.fromString("4ee46591-1f48-429e-8af7-3d2f6f663adc");
        settingsRepo.put("chat4j.chat.render.conversation.%s.mode".formatted(conversationId), "MARKDOWN");

        var subject = new AssistantRenderModeSettingsCoordinator(settingsRepo);

        AssistantRenderMode mode = subject.resolveConversationMode(conversationId, AssistantRenderMode.PREVIEW);

        assertThat(mode).isEqualTo(AssistantRenderMode.PREVIEW);
    }

    @Test
    @DisplayName("Persist conversation mode stores global PREVIEW/MARKDOWN value")
    void persistConversationMode_whenCalled_persistsGlobalSetting() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("assistant-mode-persist");
        UUID conversationId = UUID.fromString("7e6be557-f45b-4e31-b6b7-43218e13f24a");

        var subject = new AssistantRenderModeSettingsCoordinator(settingsRepo);
        subject.persistConversationMode(conversationId, AssistantRenderMode.MARKDOWN);

        assertThat(settingsRepo.get(SettingsKeys.CHAT_RENDER_MODE))
                .contains(SettingsKeys.CHAT_RENDER_MODE_MARKDOWN);
    }

    private SettingsRepo settingsRepo(String testName) {
        return new SettingsRepo(Path.of("target", "%s.properties".formatted(testName)));
    }
}
