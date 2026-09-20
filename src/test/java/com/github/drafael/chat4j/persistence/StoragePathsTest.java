package com.github.drafael.chat4j.persistence;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StoragePathsTest {

    @Test
    @DisplayName("Default paths use APPDATA on Windows when environment variable is configured")
    void defaultPaths_whenWindowsAndAppDataConfigured_usesWindowsAppDataDirectory() {
        var subject = StoragePaths.defaultPaths(
                true,
                "/Users/me",
                "/xdg/config",
                "/xdg/data",
                "/xdg/cache",
                "/xdg/state",
                "C:/Users/me/AppData/Roaming"
        );

        Path appDirectory = Path.of("C:/Users/me/AppData/Roaming").resolve("chat4j");
        assertThat(subject.appConfigDirectory()).isEqualTo(appDirectory);
        assertThat(subject.appDataDirectory()).isEqualTo(appDirectory);
        assertThat(subject.appCacheDirectory()).isEqualTo(appDirectory);
        assertThat(subject.appStateDirectory()).isEqualTo(appDirectory);
        assertThat(subject.databaseDirectory()).isEqualTo(appDirectory.resolve("data"));
    }

    @Test
    @DisplayName("Default paths use AppData/Roaming fallback on Windows when APPDATA is missing")
    void defaultPaths_whenWindowsAndAppDataMissing_usesUserHomeFallback() {
        var subject = StoragePaths.defaultPaths(true, "C:/Users/me", null, "");

        assertThat(subject.appConfigDirectory())
                .isEqualTo(Path.of("C:/Users/me", "AppData", "Roaming").resolve("chat4j"));
    }

    @Test
    @DisplayName("Non-Windows paths use each configured XDG home")
    void defaultPaths_whenNonWindowsAndXdgHomesConfigured_usesScopedHomes() {
        var subject = StoragePaths.defaultPaths(
                false,
                "/home/me",
                "/xdg/config",
                "/xdg/data",
                "/xdg/cache",
                "/xdg/state",
                null
        );

        assertThat(subject.appConfigDirectory()).isEqualTo(Path.of("/xdg/config/chat4j"));
        assertThat(subject.appDataDirectory()).isEqualTo(Path.of("/xdg/data/chat4j"));
        assertThat(subject.appCacheDirectory()).isEqualTo(Path.of("/xdg/cache/chat4j"));
        assertThat(subject.appStateDirectory()).isEqualTo(Path.of("/xdg/state/chat4j"));
    }

    @Test
    @DisplayName("Non-Windows paths use standard XDG defaults")
    void defaultPaths_whenNonWindowsAndXdgHomesMissing_usesStandardDefaults() {
        var subject = StoragePaths.defaultPaths(false, "/home/me", null, null, null, null, null);

        assertThat(subject.appConfigDirectory()).isEqualTo(Path.of("/home/me/.config/chat4j"));
        assertThat(subject.appDataDirectory()).isEqualTo(Path.of("/home/me/.local/share/chat4j"));
        assertThat(subject.appCacheDirectory()).isEqualTo(Path.of("/home/me/.cache/chat4j"));
        assertThat(subject.appStateDirectory()).isEqualTo(Path.of("/home/me/.local/state/chat4j"));
    }

    @Test
    @DisplayName("Relative XDG homes fall back to standard absolute locations")
    void defaultPaths_whenXdgHomesAreRelative_usesStandardDefaults() {
        var subject = StoragePaths.defaultPaths(
                false,
                "/home/me",
                "config",
                "data",
                "cache",
                "state",
                null
        );

        assertThat(subject.appConfigDirectory()).isEqualTo(Path.of("/home/me/.config/chat4j"));
        assertThat(subject.appDataDirectory()).isEqualTo(Path.of("/home/me/.local/share/chat4j"));
        assertThat(subject.appCacheDirectory()).isEqualTo(Path.of("/home/me/.cache/chat4j"));
        assertThat(subject.appStateDirectory()).isEqualTo(Path.of("/home/me/.local/state/chat4j"));
    }

    @Test
    @DisplayName("Storage content is assigned to its corresponding scoped home")
    void scopedFiles_whenResolved_useCorrectHomes() {
        var subject = StoragePaths.defaultPaths(
                false,
                "/home/me",
                "/xdg/config",
                "/xdg/data",
                "/xdg/cache",
                "/xdg/state",
                null
        );

        assertThat(subject.settingsFile()).isEqualTo(Path.of("/xdg/config/chat4j/chat4j.properties"));
        assertThat(subject.mcpFile()).isEqualTo(Path.of("/xdg/config/chat4j/mcp.json"));
        assertThat(subject.promptsFile()).isEqualTo(Path.of("/xdg/data/chat4j/prompts.json"));
        assertThat(subject.sqliteDatabaseFile()).isEqualTo(Path.of("/xdg/data/chat4j/data/chat4j.sqlite3"));
        assertThat(subject.attachmentsDirectory()).isEqualTo(Path.of("/xdg/data/chat4j/attachments"));
        assertThat(subject.secretsDirectory()).isEqualTo(Path.of("/xdg/data/chat4j/secrets"));
        assertThat(subject.copilotAuthFile()).isEqualTo(Path.of("/xdg/data/chat4j/secrets/copilot-auth.json"));
        assertThat(subject.codexAuthFile()).isEqualTo(Path.of("/xdg/data/chat4j/secrets/codex-auth.json"));
        assertThat(subject.databaseCredentialsFile()).isEqualTo(Path.of("/xdg/data/chat4j/secrets/db.credentials"));
        assertThat(subject.sttModelsDirectory()).isEqualTo(Path.of("/xdg/data/chat4j/stt/models"));
        assertThat(subject.cacheDirectory()).isEqualTo(Path.of("/xdg/cache/chat4j/cache"));
        assertThat(subject.catalogMetadataFile()).isEqualTo(Path.of("/xdg/cache/chat4j/catalog-index.properties"));
        assertThat(subject.jcefBundleDirectory()).isEqualTo(Path.of("/xdg/cache/chat4j/jcef-bundle"));
        assertThat(subject.sttTempDirectory()).isEqualTo(Path.of("/xdg/cache/chat4j/stt/temp"));
        assertThat(subject.logsDirectory()).isEqualTo(Path.of("/xdg/state/chat4j/logs"));
        assertThat(subject.windowStateFile()).isEqualTo(Path.of("/xdg/state/chat4j/window.properties"));
    }
}
