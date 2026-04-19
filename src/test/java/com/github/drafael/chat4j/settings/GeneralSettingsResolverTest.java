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

import static org.assertj.core.api.Assertions.assertThat;

class GeneralSettingsResolverTest {

    @Test
    @DisplayName("Resolve returns default values when settings are missing")
    void resolve_whenSettingsMissing_returnsDefaults() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("general-settings-defaults");
        var modeCoordinator = new AssistantRenderModeSettingsCoordinator(settingsRepo);
        var subject = new GeneralSettingsResolver(settingsRepo, modeCoordinator);

        GeneralSettingsResolver.GeneralSettings settings = subject.resolve(true);

        assertThat(settings.sendOnEnter()).isTrue();
        assertThat(settings.autoScrollEnabled()).isTrue();
        assertThat(settings.defaultAssistantRenderMode()).isEqualTo(AssistantRenderMode.PREVIEW);
        assertThat(settings.menuBarEnabled()).isTrue();
    }

    @Test
    @DisplayName("Resolve returns configured values when settings exist")
    void resolve_whenSettingsConfigured_returnsConfiguredValues() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("general-settings-configured");
        settingsRepo.put("send.key", "Ctrl+Enter");
        settingsRepo.put("auto.scroll", "false");
        settingsRepo.put("chat.markdown.default", "markdown");
        settingsRepo.put("menu.bar.enabled", "false");

        var modeCoordinator = new AssistantRenderModeSettingsCoordinator(settingsRepo);
        var subject = new GeneralSettingsResolver(settingsRepo, modeCoordinator);

        GeneralSettingsResolver.GeneralSettings settings = subject.resolve(true);

        assertThat(settings.sendOnEnter()).isFalse();
        assertThat(settings.autoScrollEnabled()).isFalse();
        assertThat(settings.defaultAssistantRenderMode()).isEqualTo(AssistantRenderMode.MARKDOWN);
        assertThat(settings.menuBarEnabled()).isFalse();
    }

    @Test
    @DisplayName("Resolve falls back to safe defaults when repository access fails")
    void resolve_whenRepositoryFails_returnsSafeDefaults() {
        SettingsRepo failingRepo = new ThrowingSettingsRepo();
        var modeCoordinator = new AssistantRenderModeSettingsCoordinator(failingRepo);
        var subject = new GeneralSettingsResolver(failingRepo, modeCoordinator);

        GeneralSettingsResolver.GeneralSettings settings = subject.resolve(false);

        assertThat(settings.sendOnEnter()).isTrue();
        assertThat(settings.autoScrollEnabled()).isTrue();
        assertThat(settings.defaultAssistantRenderMode()).isEqualTo(AssistantRenderMode.PREVIEW);
        assertThat(settings.menuBarEnabled()).isFalse();
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

    private static class ThrowingSettingsRepo extends SettingsRepo {

        private ThrowingSettingsRepo() {
            super(null);
        }

        @Override
        public String get(String key, String defaultValue) throws SQLException {
            throw new SQLException("forced failure");
        }
    }
}
