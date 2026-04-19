package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.storage.SettingsRepo;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class FontSettingsPersisterTest {

    @Test
    @DisplayName("Persist app font selection stores family and size settings")
    void persistAppFontSelection_whenCalled_persistsFamilyAndSize() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("font-persister-app");
        var subject = new FontSettingsPersister(settingsRepo);

        subject.persistAppFontSelection("Inter", 16);

        assertThat(settingsRepo.get(AppearancePanel.KEY_APP_FONT)).contains("Inter");
        assertThat(settingsRepo.get(AppearancePanel.KEY_APP_FONT_SIZE)).contains("16");
    }

    @Test
    @DisplayName("Persist code font family stores code font setting")
    void persistCodeFontFamily_whenCalled_persistsCodeFontFamily() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("font-persister-code");
        var subject = new FontSettingsPersister(settingsRepo);

        subject.persistCodeFontFamily("JetBrains Mono");

        assertThat(settingsRepo.get(AppearancePanel.KEY_CODE_FONT)).contains("JetBrains Mono");
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
