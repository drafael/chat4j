package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.storage.SettingsRepo;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
    @DisplayName("Resolve default mode returns stored markdown setting")
    void resolveDefaultMode_whenStoredMarkdown_returnsMarkdown() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("assistant-mode-default-markdown");
        settingsRepo.put("chat.markdown.default", "markdown");

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
    @DisplayName("Resolve conversation mode returns stored mode when present")
    void resolveConversationMode_whenStoredValueExists_returnsStoredMode() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("assistant-mode-conv-stored");
        UUID conversationId = UUID.fromString("4ee46591-1f48-429e-8af7-3d2f6f663adc");
        settingsRepo.put("chat.markdown.conv.%s".formatted(conversationId), "markdown");

        var subject = new AssistantRenderModeSettingsCoordinator(settingsRepo);

        AssistantRenderMode mode = subject.resolveConversationMode(conversationId, AssistantRenderMode.PREVIEW);

        assertThat(mode).isEqualTo(AssistantRenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Persist conversation mode stores value under conversation key")
    void persistConversationMode_whenCalled_persistsSetting() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("assistant-mode-persist");
        UUID conversationId = UUID.fromString("7e6be557-f45b-4e31-b6b7-43218e13f24a");

        var subject = new AssistantRenderModeSettingsCoordinator(settingsRepo);
        subject.persistConversationMode(conversationId, AssistantRenderMode.MARKDOWN);

        assertThat(settingsRepo.get("chat.markdown.conv.%s".formatted(conversationId))).contains("markdown");
    }

    private SettingsRepo settingsRepo(String dbName) throws SQLException {
        DataSource dataSource = createDataSource(dbName);
        createSettingsTable(dataSource);
        return new SettingsRepo(dataSource);
    }

    private DataSource createDataSource(String dbName) {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(dbName));
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private void createSettingsTable(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS settings (\"key\" VARCHAR(100) PRIMARY KEY, \"value\" VARCHAR(500))"
             )
        ) {
            statement.execute();
        }
    }
}
