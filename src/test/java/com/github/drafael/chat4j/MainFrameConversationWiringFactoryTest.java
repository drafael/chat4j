package com.github.drafael.chat4j;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.settings.AssistantRenderModeSettingsCoordinator;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.storage.ConversationRepo;
import com.github.drafael.chat4j.storage.PersistedMessageCounter;
import com.github.drafael.chat4j.storage.SettingsRepo;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainFrameConversationWiringFactoryTest {

    @Test
    @DisplayName("Create builds non-null conversation wiring graph")
    void create_whenCalled_buildsConversationWiring() throws Exception {
        var subject = new MainFrameConversationWiringFactory();
        var conversationRepo = new ConversationRepo(createDataSource("mainframe-conversation-wiring"));
        var settingsRepo = settingsRepo("mainframe-conversation-wiring-settings");
        var assistantRenderModeSettingsCoordinator = new AssistantRenderModeSettingsCoordinator(settingsRepo);

        MainFrameConversationWiringFactory.ConversationWiring wiring = subject.create(
                conversationRepo,
                new PersistedMessageCounter(),
                assistantRenderModeSettingsCoordinator,
                conversationId -> AssistantRenderMode.PREVIEW
        );

        assertThat(wiring.conversationLoadCoordinator()).isNotNull();
        assertThat(wiring.conversationLoadResultPlanner()).isNotNull();
        assertThat(wiring.conversationLoadApplyDispatchCoordinator()).isNotNull();
        assertThat(wiring.conversationPersistenceCoordinator()).isNotNull();
        assertThat(wiring.assistantMessageCompletionFlowCoordinator()).isNotNull();
        assertThat(wiring.currentConversationSaveCoordinator()).isNotNull();

        var saveResult = wiring.currentConversationSaveCoordinator().save(
                UUID.randomUUID(),
                AssistantRenderMode.MARKDOWN,
                emptyList(),
                "OpenAI > gpt-4.1",
                AssistantRenderMode.PREVIEW,
                ReasoningLevel.OFF,
                false,
                null
        );

        assertThat(saveResult.saved()).isFalse();
        assertThat(saveResult.conversationId()).isNotNull();
    }

    @Test
    @DisplayName("Create validates required dependencies")
    void create_whenRequiredDependencyMissing_throwsException() throws Exception {
        var subject = new MainFrameConversationWiringFactory();
        var conversationRepo = new ConversationRepo(createDataSource("mainframe-conversation-wiring-validation"));
        var settingsRepo = settingsRepo("mainframe-conversation-wiring-validation-settings");
        var assistantRenderModeSettingsCoordinator = new AssistantRenderModeSettingsCoordinator(settingsRepo);

        assertThatThrownBy(() -> subject.create(
                null,
                new PersistedMessageCounter(),
                assistantRenderModeSettingsCoordinator,
                conversationId -> AssistantRenderMode.PREVIEW
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("conversationRepo");

        assertThatThrownBy(() -> subject.create(
                conversationRepo,
                new PersistedMessageCounter(),
                assistantRenderModeSettingsCoordinator,
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("conversationModeResolver");
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
             )) {
            statement.execute();
        }
    }
}
