package com.github.drafael.chat4j;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.util.MenuPopupVisibleRunner;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainFrameLifecycleWiringFactoryTest {

    @Test
    @DisplayName("Create builds non-null lifecycle wiring graph")
    void create_whenCalled_buildsLifecycleWiring() throws Exception {
        var subject = new MainFrameLifecycleWiringFactory();
        var settingsRepo = settingsRepo("mainframe-lifecycle-wiring");

        MainFrameLifecycleWiringFactory.LifecycleWiring wiring = subject.create(
                settingsRepo,
                new MenuPopupVisibleRunner()
        );

        assertThat(wiring.modelMenuDirtyRefreshCoordinator()).isNotNull();
        assertThat(wiring.modelMenuDirtyRefreshTriggerCoordinator()).isNotNull();
        assertThat(wiring.lookAndFeelMenuRefreshCoordinator()).isNotNull();
        assertThat(wiring.windowStateSettingsCoordinator()).isNotNull();
        assertThat(wiring.windowStateRestoreCoordinator()).isNotNull();
    }

    @Test
    @DisplayName("Create validates required dependencies")
    void create_whenRequiredDependencyMissing_throwsException() throws Exception {
        var subject = new MainFrameLifecycleWiringFactory();
        var settingsRepo = settingsRepo("mainframe-lifecycle-wiring-validation");

        assertThatThrownBy(() -> subject.create(null, new MenuPopupVisibleRunner()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("settingsRepo");

        assertThatThrownBy(() -> subject.create(settingsRepo, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("menuPopupVisibleRunner");
    }

    private SettingsRepository settingsRepo(String dbName) throws SQLException {
        DataSource dataSource = createDataSource(dbName);
        createSettingsTable(dataSource);
        return new SettingsRepository(dataSource);
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
