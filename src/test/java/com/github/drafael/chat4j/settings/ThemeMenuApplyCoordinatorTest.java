package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeMenuApplyCoordinatorTest {

    @Test
    @DisplayName("Apply runs theme side effects in order and returns success")
    void apply_whenSuccessful_runsThemeSideEffectsInOrder() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("theme-apply-success");
        List<String> calls = new ArrayList<>();
        var themeSettingsResolver = new RecordingThemeSettingsResolver(settingsRepo, calls);

        var subject = new ThemeMenuApplyCoordinator(
                themeSettingsResolver,
                settingsRepo,
                className -> calls.add("laf:%s".formatted(className)),
                () -> calls.add("icons"),
                repo -> calls.add("fonts:%s".formatted(repo == settingsRepo)),
                () -> calls.add("refresh")
        );

        ThemeMenuApplyCoordinator.ApplyResult applyResult = subject.apply(
                "GitHub",
                "theme.GitHub",
                () -> calls.add("dirty"),
                () -> calls.add("sync-theme"),
                () -> calls.add("sync-font")
        );

        assertThat(applyResult.success()).isTrue();
        assertThat(applyResult.errorMessage()).isNull();
        assertThat(calls).containsExactly(
                "laf:theme.GitHub",
                "icons",
                "dirty",
                "fonts:true",
                "refresh",
                "persist:GitHub",
                "sync-theme",
                "sync-font"
        );
    }

    @Test
    @DisplayName("Apply returns failure when look and feel switch fails and skips downstream actions")
    void apply_whenLookAndFeelSwitchFails_returnsFailureAndSkipsDownstreamActions() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("theme-apply-failure");
        List<String> calls = new ArrayList<>();
        var themeSettingsResolver = new RecordingThemeSettingsResolver(settingsRepo, calls);

        AtomicInteger dirtyCalls = new AtomicInteger();
        AtomicInteger syncThemeCalls = new AtomicInteger();
        AtomicInteger syncFontCalls = new AtomicInteger();

        var subject = new ThemeMenuApplyCoordinator(
                themeSettingsResolver,
                settingsRepo,
                className -> {
                    throw new IllegalStateException("boom");
                },
                () -> calls.add("icons"),
                repo -> calls.add("fonts"),
                () -> calls.add("refresh")
        );

        ThemeMenuApplyCoordinator.ApplyResult applyResult = subject.apply(
                "GitHub",
                "theme.GitHub",
                dirtyCalls::incrementAndGet,
                syncThemeCalls::incrementAndGet,
                syncFontCalls::incrementAndGet
        );

        assertThat(applyResult.success()).isFalse();
        assertThat(applyResult.errorMessage()).isEqualTo("boom");
        assertThat(calls).isEmpty();
        assertThat(themeSettingsResolver.persistCalls.get()).isZero();
        assertThat(dirtyCalls.get()).isZero();
        assertThat(syncThemeCalls.get()).isZero();
        assertThat(syncFontCalls.get()).isZero();
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

    private static class RecordingThemeSettingsResolver extends ThemeSettingsResolver {

        private final List<String> calls;
        private final AtomicInteger persistCalls = new AtomicInteger();

        private RecordingThemeSettingsResolver(SettingsRepository settingsRepo, List<String> calls) {
            super(settingsRepo);
            this.calls = calls;
        }

        @Override
        public void persistSelectedTheme(String themeName) {
            persistCalls.incrementAndGet();
            calls.add("persist:%s".formatted(themeName));
        }
    }
}
