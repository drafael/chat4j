package com.github.drafael.chat4j.persistence;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class StoragePathsTest {

    @TempDir
    private Path tempDir;

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
        Path xdgHome = tempDir.resolve("xdg");
        var subject = StoragePaths.defaultPaths(
                false,
                tempDir.resolve("home").toString(),
                xdgHome.resolve("config").toString(),
                xdgHome.resolve("data").toString(),
                xdgHome.resolve("cache").toString(),
                xdgHome.resolve("state").toString(),
                null
        );

        assertThat(subject.appConfigDirectory()).isEqualTo(xdgHome.resolve("config/chat4j"));
        assertThat(subject.appDataDirectory()).isEqualTo(xdgHome.resolve("data/chat4j"));
        assertThat(subject.appCacheDirectory()).isEqualTo(xdgHome.resolve("cache/chat4j"));
        assertThat(subject.appStateDirectory()).isEqualTo(xdgHome.resolve("state/chat4j"));
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
        Path xdgHome = tempDir.resolve("xdg");
        var subject = StoragePaths.defaultPaths(
                false,
                tempDir.resolve("home").toString(),
                xdgHome.resolve("config").toString(),
                xdgHome.resolve("data").toString(),
                xdgHome.resolve("cache").toString(),
                xdgHome.resolve("state").toString(),
                null
        );

        assertThat(subject.settingsFile()).isEqualTo(xdgHome.resolve("config/chat4j/chat4j.properties"));
        assertThat(subject.mcpFile()).isEqualTo(xdgHome.resolve("config/chat4j/mcp.json"));
        assertThat(subject.promptsFile()).isEqualTo(xdgHome.resolve("data/chat4j/prompts.json"));
        assertThat(subject.sqliteDatabaseFile()).isEqualTo(xdgHome.resolve("data/chat4j/data/chat4j.sqlite3"));
        assertThat(subject.attachmentsDirectory()).isEqualTo(xdgHome.resolve("data/chat4j/attachments"));
        assertThat(subject.secretsDirectory()).isEqualTo(xdgHome.resolve("data/chat4j/secrets"));
        assertThat(subject.copilotAuthFile()).isEqualTo(xdgHome.resolve("data/chat4j/secrets/copilot-auth.json"));
        assertThat(subject.codexAuthFile()).isEqualTo(xdgHome.resolve("data/chat4j/secrets/codex-auth.json"));
        assertThat(subject.databaseCredentialsFile()).isEqualTo(xdgHome.resolve("data/chat4j/secrets/db.credentials"));
        assertThat(subject.sttModelsDirectory()).isEqualTo(xdgHome.resolve("data/chat4j/stt/models"));
        assertThat(subject.cacheDirectory()).isEqualTo(xdgHome.resolve("cache/chat4j/cache"));
        assertThat(subject.catalogMetadataFile()).isEqualTo(xdgHome.resolve("cache/chat4j/catalog-index.properties"));
        assertThat(subject.jcefBundleDirectory()).isEqualTo(xdgHome.resolve("cache/chat4j/jcef-bundle"));
        assertThat(subject.sttTempDirectory()).isEqualTo(xdgHome.resolve("cache/chat4j/stt/temp"));
        assertThat(subject.logsDirectory()).isEqualTo(xdgHome.resolve("state/chat4j/logs"));
        assertThat(subject.windowStateFile()).isEqualTo(xdgHome.resolve("state/chat4j/window.properties"));
    }
}
