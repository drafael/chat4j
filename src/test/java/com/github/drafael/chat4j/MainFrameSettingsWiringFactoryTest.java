package com.github.drafael.chat4j;

import com.github.drafael.chat4j.settings.RenderModeChangeUiApplyCoordinator;
import com.github.drafael.chat4j.settings.RenderModeSelectionResolver;
import com.github.drafael.chat4j.settings.FontMenuSelectionApplyCoordinator;
import com.github.drafael.chat4j.settings.FontMenuSelectionSynchronizer;
import com.github.drafael.chat4j.settings.FontPreviewApplier;
import com.github.drafael.chat4j.settings.FontSelectionNormalizer;
import com.github.drafael.chat4j.settings.GeneralSettingsUiApplyCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuSelectionApplyCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuSelectionSynchronizer;
import com.github.drafael.chat4j.storage.SettingsRepo;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainFrameSettingsWiringFactoryTest {

    @Test
    @DisplayName("Create builds non-null settings wiring graph")
    void create_whenCalled_buildsSettingsWiring() throws Exception {
        var subject = new MainFrameSettingsWiringFactory();
        var settingsRepo = settingsRepo("mainframe-settings-wiring");

        MainFrameSettingsWiringFactory.SettingsWiring wiring = subject.create(
                settingsRepo,
                new RenderModeSelectionResolver(),
                new RenderModeChangeUiApplyCoordinator(),
                new GeneralSettingsUiApplyCoordinator(),
                new FontSelectionNormalizer(),
                new FontPreviewApplier(),
                new FontMenuSelectionSynchronizer(),
                new FontMenuSelectionApplyCoordinator(),
                new ThemeMenuSelectionSynchronizer(),
                new ThemeMenuSelectionApplyCoordinator()
        );

        assertThat(wiring.renderModeSettingsCoordinator()).isNotNull();
        assertThat(wiring.renderModeChangeCoordinator()).isNotNull();
        assertThat(wiring.renderModeChangeDispatchCoordinator()).isNotNull();
        assertThat(wiring.generalSettingsResolver()).isNotNull();
        assertThat(wiring.generalSettingsApplyCoordinator()).isNotNull();
        assertThat(wiring.generalSettingsApplyDispatchCoordinator()).isNotNull();
        assertThat(wiring.fontSettingsResolver()).isNotNull();
        assertThat(wiring.fontSettingsPersister()).isNotNull();
        assertThat(wiring.fontMenuApplyCoordinator()).isNotNull();
        assertThat(wiring.fontMenuSelectionRefreshCoordinator()).isNotNull();
        assertThat(wiring.fontMenuSelectionDispatchCoordinator()).isNotNull();
        assertThat(wiring.fontMenuSelectionFlowCoordinator()).isNotNull();
        assertThat(wiring.appFontSizeStepResolver()).isNotNull();
        assertThat(wiring.appFontSizeAdjustCoordinator()).isNotNull();
        assertThat(wiring.themeSettingsResolver()).isNotNull();
        assertThat(wiring.themeMenuApplyCoordinator()).isNotNull();
        assertThat(wiring.themeMenuSelectionRefreshCoordinator()).isNotNull();
        assertThat(wiring.themeMenuSelectionDispatchCoordinator()).isNotNull();
        assertThat(wiring.themeMenuSelectionFlowCoordinator()).isNotNull();
    }

    @Test
    @DisplayName("Create validates required dependencies")
    void create_whenRequiredDependencyMissing_throwsException() {
        var subject = new MainFrameSettingsWiringFactory();

        assertThatThrownBy(() -> subject.create(
                null,
                new RenderModeSelectionResolver(),
                new RenderModeChangeUiApplyCoordinator(),
                new GeneralSettingsUiApplyCoordinator(),
                new FontSelectionNormalizer(),
                new FontPreviewApplier(),
                new FontMenuSelectionSynchronizer(),
                new FontMenuSelectionApplyCoordinator(),
                new ThemeMenuSelectionSynchronizer(),
                new ThemeMenuSelectionApplyCoordinator()
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("settingsRepo");
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
