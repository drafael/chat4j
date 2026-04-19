package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.storage.SettingsRepo;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.awt.Rectangle;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WindowStateSettingsCoordinatorTest {

    @Test
    @DisplayName("Save persists window bounds entries")
    void save_whenCalled_persistsWindowBoundsSettings() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("window-state-save");
        var subject = new WindowStateSettingsCoordinator(settingsRepo);

        subject.save(new Rectangle(10, 20, 900, 600));

        assertThat(settingsRepo.get("window.x")).contains("10");
        assertThat(settingsRepo.get("window.y")).contains("20");
        assertThat(settingsRepo.get("window.width")).contains("900");
        assertThat(settingsRepo.get("window.height")).contains("600");
    }

    @Test
    @DisplayName("Load returns bounds when stored window intersects visible screen")
    void loadIfVisible_whenStoredBoundsIntersectScreen_returnsBounds() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("window-state-visible");
        settingsRepo.put("window.x", "100");
        settingsRepo.put("window.y", "80");
        settingsRepo.put("window.width", "800");
        settingsRepo.put("window.height", "500");

        var subject = new WindowStateSettingsCoordinator(settingsRepo);

        Optional<Rectangle> loaded = subject.loadIfVisible(new Rectangle(0, 0, 1920, 1080));

        assertThat(loaded).contains(new Rectangle(100, 80, 800, 500));
    }

    @Test
    @DisplayName("Load returns empty when stored bounds are outside visible screen")
    void loadIfVisible_whenStoredBoundsAreOutsideScreen_returnsEmpty() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("window-state-offscreen");
        settingsRepo.put("window.x", "4000");
        settingsRepo.put("window.y", "3000");
        settingsRepo.put("window.width", "800");
        settingsRepo.put("window.height", "500");

        var subject = new WindowStateSettingsCoordinator(settingsRepo);

        Optional<Rectangle> loaded = subject.loadIfVisible(new Rectangle(0, 0, 1920, 1080));

        assertThat(loaded).isEmpty();
    }

    @Test
    @DisplayName("Load returns empty when stored values are invalid")
    void loadIfVisible_whenStoredValuesAreInvalid_returnsEmpty() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("window-state-invalid");
        settingsRepo.put("window.x", "abc");

        var subject = new WindowStateSettingsCoordinator(settingsRepo);

        Optional<Rectangle> loaded = subject.loadIfVisible(new Rectangle(0, 0, 1920, 1080));

        assertThat(loaded).isEmpty();
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
