package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FontMenuApplyCoordinatorTest {

    @Test
    @DisplayName("Apply app font normalizes input, updates preview, persists settings, and syncs")
    void applyAppFontSelection_whenSuccessful_appliesPreviewPersistsAndSyncs() throws Exception {
        var normalizer = new RecordingFontSelectionNormalizer();
        normalizer.appResult = new FontSelectionNormalizer.AppFontSelection("Inter", 14);
        var previewApplier = new RecordingFontPreviewApplier();
        var persister = new RecordingFontSettingsPersister(settingsRepo("font-menu-apply-app-success"));
        AtomicInteger refreshCalls = new AtomicInteger();
        AtomicInteger syncCalls = new AtomicInteger();

        var subject = new FontMenuApplyCoordinator(
                normalizer,
                previewApplier,
                persister,
                refreshCalls::incrementAndGet
        );

        FontMenuApplyCoordinator.ApplyResult result = subject.applyAppFontSelection(
                "System Default",
                13,
                Set.of("System Default", "Inter"),
                syncCalls::incrementAndGet
        );

        assertThat(result.success()).isTrue();
        assertThat(result.errorMessage()).isNull();
        assertThat(normalizer.lastAppRequestedFamily).isEqualTo("System Default");
        assertThat(normalizer.lastAppRequestedSize).isEqualTo(13);
        assertThat(normalizer.lastAvailableAppFamilies).containsExactlyInAnyOrder("System Default", "Inter");
        assertThat(previewApplier.calls).containsExactly("app:Inter:14");
        assertThat(refreshCalls.get()).isEqualTo(1);
        assertThat(persister.calls).containsExactly("persist-app:Inter:14");
        assertThat(syncCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Apply app font returns failure when persistence fails but still syncs")
    void applyAppFontSelection_whenPersistenceFails_returnsFailureAndStillSyncs() throws Exception {
        var normalizer = new RecordingFontSelectionNormalizer();
        normalizer.appResult = new FontSelectionNormalizer.AppFontSelection("Inter", 14);
        var previewApplier = new RecordingFontPreviewApplier();
        var persister = new RecordingFontSettingsPersister(settingsRepo("font-menu-apply-app-failure"));
        persister.throwOnPersistApp = true;
        AtomicInteger refreshCalls = new AtomicInteger();
        AtomicInteger syncCalls = new AtomicInteger();

        var subject = new FontMenuApplyCoordinator(
                normalizer,
                previewApplier,
                persister,
                refreshCalls::incrementAndGet
        );

        FontMenuApplyCoordinator.ApplyResult result = subject.applyAppFontSelection(
                "Inter",
                14,
                Set.of("Inter"),
                syncCalls::incrementAndGet
        );

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("boom-app");
        assertThat(previewApplier.calls).containsExactly("app:Inter:14");
        assertThat(refreshCalls.get()).isEqualTo(1);
        assertThat(persister.calls).containsExactly("persist-app:Inter:14");
        assertThat(syncCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Apply code font normalizes input, updates preview, persists settings, and syncs")
    void applyCodeFontSelection_whenSuccessful_appliesPreviewPersistsAndSyncs() throws Exception {
        var normalizer = new RecordingFontSelectionNormalizer();
        normalizer.codeResult = "JetBrains Mono";
        var previewApplier = new RecordingFontPreviewApplier();
        var persister = new RecordingFontSettingsPersister(settingsRepo("font-menu-apply-code-success"));
        AtomicInteger refreshCalls = new AtomicInteger();
        AtomicInteger syncCalls = new AtomicInteger();

        var subject = new FontMenuApplyCoordinator(
                normalizer,
                previewApplier,
                persister,
                refreshCalls::incrementAndGet
        );

        FontMenuApplyCoordinator.ApplyResult result = subject.applyCodeFontSelection(
                "Monospaced",
                Set.of("Monospaced", "JetBrains Mono"),
                syncCalls::incrementAndGet
        );

        assertThat(result.success()).isTrue();
        assertThat(result.errorMessage()).isNull();
        assertThat(normalizer.lastCodeRequestedFamily).isEqualTo("Monospaced");
        assertThat(normalizer.lastAvailableCodeFamilies).containsExactlyInAnyOrder("Monospaced", "JetBrains Mono");
        assertThat(previewApplier.calls).containsExactly("code:JetBrains Mono");
        assertThat(refreshCalls.get()).isEqualTo(1);
        assertThat(persister.calls).containsExactly("persist-code:JetBrains Mono");
        assertThat(syncCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Apply code font returns failure when persistence fails but still syncs")
    void applyCodeFontSelection_whenPersistenceFails_returnsFailureAndStillSyncs() throws Exception {
        var normalizer = new RecordingFontSelectionNormalizer();
        normalizer.codeResult = "JetBrains Mono";
        var previewApplier = new RecordingFontPreviewApplier();
        var persister = new RecordingFontSettingsPersister(settingsRepo("font-menu-apply-code-failure"));
        persister.throwOnPersistCode = true;
        AtomicInteger refreshCalls = new AtomicInteger();
        AtomicInteger syncCalls = new AtomicInteger();

        var subject = new FontMenuApplyCoordinator(
                normalizer,
                previewApplier,
                persister,
                refreshCalls::incrementAndGet
        );

        FontMenuApplyCoordinator.ApplyResult result = subject.applyCodeFontSelection(
                "Monospaced",
                Set.of("Monospaced", "JetBrains Mono"),
                syncCalls::incrementAndGet
        );

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("boom-code");
        assertThat(previewApplier.calls).containsExactly("code:JetBrains Mono");
        assertThat(refreshCalls.get()).isEqualTo(1);
        assertThat(persister.calls).containsExactly("persist-code:JetBrains Mono");
        assertThat(syncCalls.get()).isEqualTo(1);
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

    private static class RecordingFontSelectionNormalizer extends FontSelectionNormalizer {

        private String lastAppRequestedFamily;
        private int lastAppRequestedSize;
        private Set<String> lastAvailableAppFamilies;
        private String lastCodeRequestedFamily;
        private Set<String> lastAvailableCodeFamilies;
        private AppFontSelection appResult = new AppFontSelection("System Default", 13);
        private String codeResult = "Monospaced";

        @Override
        public AppFontSelection normalizeAppSelection(String requestedFamily, int requestedSize, Set<String> availableFamilies) {
            lastAppRequestedFamily = requestedFamily;
            lastAppRequestedSize = requestedSize;
            lastAvailableAppFamilies = availableFamilies;
            return appResult;
        }

        @Override
        public String normalizeCodeFamily(String requestedFamily, Set<String> availableFamilies) {
            lastCodeRequestedFamily = requestedFamily;
            lastAvailableCodeFamilies = availableFamilies;
            return codeResult;
        }
    }

    private static class RecordingFontPreviewApplier extends FontPreviewApplier {

        private final List<String> calls = new ArrayList<>();

        @Override
        public void applyAppFont(String family, int size) {
            calls.add("app:%s:%d".formatted(family, size));
        }

        @Override
        public void applyCodeFont(String family) {
            calls.add("code:%s".formatted(family));
        }
    }

    private static class RecordingFontSettingsPersister extends FontSettingsPersister {

        private final List<String> calls = new ArrayList<>();
        private boolean throwOnPersistApp;
        private boolean throwOnPersistCode;

        private RecordingFontSettingsPersister(SettingsRepository settingsRepo) {
            super(settingsRepo);
        }

        @Override
        public void persistAppFontSelection(String family, int size) throws SQLException {
            calls.add("persist-app:%s:%d".formatted(family, size));
            if (throwOnPersistApp) {
                throw new SQLException("boom-app");
            }
        }

        @Override
        public void persistCodeFontFamily(String family) throws SQLException {
            calls.add("persist-code:%s".formatted(family));
            if (throwOnPersistCode) {
                throw new SQLException("boom-code");
            }
        }
    }
}
