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

class ThemeSettingsResolverTest {

    @Test
    @DisplayName("Resolve selected theme returns default when setting is missing")
    void resolveSelectedTheme_whenSettingMissing_returnsDefaultTheme() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("theme-settings-default");
        var subject = new ThemeSettingsResolver(settingsRepo);

        String selectedTheme = subject.resolveSelectedTheme(ThemeSettingsResolver.DEFAULT_THEME);

        assertThat(selectedTheme).isEqualTo(ThemeSettingsResolver.DEFAULT_THEME);
    }

    @Test
    @DisplayName("Resolve selected theme returns stored value when configured")
    void resolveSelectedTheme_whenStoredValueExists_returnsStoredTheme() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("theme-settings-stored");
        settingsRepo.put("theme", "Solarized Dark");

        var subject = new ThemeSettingsResolver(settingsRepo);

        String selectedTheme = subject.resolveSelectedTheme(ThemeSettingsResolver.DEFAULT_THEME);

        assertThat(selectedTheme).isEqualTo("Solarized Dark");
    }

    @Test
    @DisplayName("Resolve selected theme falls back to default when settings access fails")
    void resolveSelectedTheme_whenSettingsAccessFails_returnsDefaultTheme() {
        var subject = new ThemeSettingsResolver(new ThrowingSettingsRepo());

        String selectedTheme = subject.resolveSelectedTheme(ThemeSettingsResolver.DEFAULT_THEME);

        assertThat(selectedTheme).isEqualTo(ThemeSettingsResolver.DEFAULT_THEME);
    }

    @Test
    @DisplayName("Persist selected theme stores theme setting")
    void persistSelectedTheme_whenCalled_persistsThemeSetting() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("theme-settings-persist");
        var subject = new ThemeSettingsResolver(settingsRepo);

        subject.persistSelectedTheme("GitHub Dark");

        assertThat(settingsRepo.get("theme")).contains("GitHub Dark");
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
