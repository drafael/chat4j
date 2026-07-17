package com.github.drafael.chat4j.persistence;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StoragePathsTest {

    @Test
    @DisplayName("Default paths use APPDATA on Windows when environment variable is configured")
    void defaultPaths_whenWindowsAndAppDataConfigured_usesWindowsAppDataDirectory() {
        var subject = StoragePaths.defaultPaths("Windows 11", "/Users/me", "/xdg/ignored", "C:/Users/me/AppData/Roaming");

        assertThat(subject.appConfigDirectory())
                .isEqualTo(Path.of("C:/Users/me/AppData/Roaming").resolve("chat4j"));
        assertThat(subject.databaseDirectory())
                .isEqualTo(Path.of("C:/Users/me/AppData/Roaming").resolve("chat4j").resolve("data"));
    }

    @Test
    @DisplayName("Default paths use AppData/Roaming fallback on Windows when APPDATA is missing")
    void defaultPaths_whenWindowsAndAppDataMissing_usesUserHomeFallback() {
        var subject = StoragePaths.defaultPaths("Windows 10", "C:/Users/me", null, "");

        assertThat(subject.appConfigDirectory())
                .isEqualTo(Path.of("C:/Users/me", "AppData", "Roaming").resolve("chat4j"));
    }

    @Test
    @DisplayName("Default paths use XDG config home on non-Windows and fallback to .config")
    void defaultPaths_whenNonWindows_usesXdgThenDotConfigFallback() {
        var withXdg = StoragePaths.defaultPaths("Linux", "/home/me", "/tmp/xdg", null);
        var fallback = StoragePaths.defaultPaths("Linux", "/home/me", null, null);

        assertThat(withXdg.appConfigDirectory()).isEqualTo(Path.of("/tmp/xdg").resolve("chat4j"));
        assertThat(fallback.appConfigDirectory()).isEqualTo(Path.of("/home/me", ".config").resolve("chat4j"));
    }

    @Test
    @DisplayName("SQLite database path uses explicit sqlite3 file under data directory")
    void sqliteDatabaseFile_whenResolved_usesSqlite3FileName() {
        var subject = StoragePaths.defaultPaths("Linux", "/home/me", "/tmp/xdg", null);

        assertThat(subject.sqliteDatabaseFile())
                .isEqualTo(Path.of("/tmp/xdg").resolve("chat4j").resolve("data").resolve("chat4j.sqlite3"));
    }

    @Test
    @DisplayName("JCEF bundle path is rooted under the application config directory")
    void jcefBundleDirectory_whenResolved_usesApplicationConfigDirectory() {
        var subject = StoragePaths.defaultPaths("Linux", "/home/me", "/tmp/xdg", null);

        assertThat(subject.jcefBundleDirectory())
                .isEqualTo(Path.of("/tmp/xdg").resolve("chat4j").resolve("jcef-bundle"));
    }

    @Test
    @DisplayName("Runtime and legacy cache paths are separate under the application config directory")
    void cacheDirectories_whenResolved_useCanonicalAndLegacyLocations() {
        var subject = StoragePaths.defaultPaths("Linux", "/home/me", "/tmp/xdg", null);

        assertThat(subject.cacheDirectory()).isEqualTo(Path.of("/tmp/xdg", "chat4j", "cache"));
        assertThat(subject.legacyModelsCacheDirectory()).isEqualTo(Path.of("/tmp/xdg", "chat4j", "models-cache"));
    }

    @Test
    @DisplayName("Speech to Text directories are rooted under the application config directory")
    void sttDirectories_whenResolved_useApplicationConfigDirectory() {
        var subject = StoragePaths.defaultPaths("Linux", "/home/me", "/tmp/xdg", null);

        assertThat(subject.sttModelsDirectory())
                .isEqualTo(Path.of("/tmp/xdg").resolve("chat4j").resolve("stt").resolve("models"));
        assertThat(subject.sttTempDirectory())
                .isEqualTo(Path.of("/tmp/xdg").resolve("chat4j").resolve("stt").resolve("temp"));
    }
}
