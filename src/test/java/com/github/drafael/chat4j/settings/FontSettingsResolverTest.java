package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.storage.SettingsRepo;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FontSettingsResolverTest {

    @Test
    @DisplayName("Resolve menu selection uses defaults when settings are missing")
    void resolveMenuSelection_whenSettingsMissing_usesDefaults() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("font-settings-defaults");
        var subject = new FontSettingsResolver(settingsRepo);

        FontSettingsResolver.FontMenuSelection selection = subject.resolveMenuSelection(
                Set.of(AppearancePanel.DEFAULT_APP_FONT),
                Set.of(AppearancePanel.defaultAppFontSize()),
                Set.of(AppearancePanel.DEFAULT_CODE_FONT)
        );

        assertThat(selection.appFontFamily()).isEqualTo(AppearancePanel.DEFAULT_APP_FONT);
        assertThat(selection.appFontSize()).isEqualTo(AppearancePanel.defaultAppFontSize());
        assertThat(selection.codeFontFamily()).isEqualTo(AppearancePanel.DEFAULT_CODE_FONT);
    }

    @Test
    @DisplayName("Resolve menu selection falls back for unavailable font families")
    void resolveMenuSelection_whenConfiguredFamiliesAreUnavailable_fallsBackToDefaults() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("font-settings-family-fallback");
        settingsRepo.put(AppearancePanel.KEY_APP_FONT, "Unavailable UI Font");
        settingsRepo.put(AppearancePanel.KEY_CODE_FONT, "Unavailable Code Font");
        settingsRepo.put(AppearancePanel.KEY_APP_FONT_SIZE, "18");

        var subject = new FontSettingsResolver(settingsRepo);

        FontSettingsResolver.FontMenuSelection selection = subject.resolveMenuSelection(
                Set.of(AppearancePanel.DEFAULT_APP_FONT, "Inter"),
                Set.of(14, 16, 18),
                Set.of(AppearancePanel.DEFAULT_CODE_FONT, "JetBrains Mono")
        );

        assertThat(selection.appFontFamily()).isEqualTo(AppearancePanel.DEFAULT_APP_FONT);
        assertThat(selection.appFontSize()).isEqualTo(18);
        assertThat(selection.codeFontFamily()).isEqualTo(AppearancePanel.DEFAULT_CODE_FONT);
    }

    @Test
    @DisplayName("Resolve app font size falls back to default when value is invalid")
    void resolveAppFontSizeSetting_whenValueIsInvalid_returnsDefaultNormalizedSize() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("font-settings-size-invalid");
        settingsRepo.put(AppearancePanel.KEY_APP_FONT_SIZE, "not-a-number");

        var subject = new FontSettingsResolver(settingsRepo);

        int resolvedSize = subject.resolveAppFontSizeSetting();

        assertThat(resolvedSize).isEqualTo(AppearancePanel.normalizeAppFontSize(AppearancePanel.defaultAppFontSize()));
    }

    @Test
    @DisplayName("Resolve methods return defaults when repository access fails")
    void resolveMethods_whenRepositoryFails_returnDefaults() {
        SettingsRepo failingRepo = new ThrowingSettingsRepo();
        var subject = new FontSettingsResolver(failingRepo);

        assertThat(subject.resolveAppFontFamilySetting()).isEqualTo(AppearancePanel.DEFAULT_APP_FONT);
        assertThat(subject.resolveCodeFontFamilySetting()).isEqualTo(AppearancePanel.DEFAULT_CODE_FONT);
        assertThat(subject.resolveAppFontSizeSetting())
                .isEqualTo(AppearancePanel.normalizeAppFontSize(AppearancePanel.defaultAppFontSize()));
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
