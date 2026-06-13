package com.github.drafael.chat4j.storage;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.nio.file.Path;

public final class StoragePaths {

    private static final String APP_NAME = "chat4j";
    private static final String DB_NAME = "chat4j";
    private static final String SQLITE_DB_FILE_NAME = "chat4j.sqlite3";
    private static final String SQLITE_MIGRATING_DB_FILE_NAME = "chat4j.sqlite3.migrating";
    private static final String H2_MIGRATING_DB_NAME = "chat4j-h2-migrating";
    private static final String WINDOWS_APPDATA_ENV = "APPDATA";

    private final Path configHome;

    private StoragePaths(Path configHome) {
        this.configHome = configHome;
    }

    public static StoragePaths defaultPaths() {
        return defaultPaths(
                System.getProperty("os.name"),
                System.getProperty("user.home"),
                System.getenv("XDG_CONFIG_HOME"),
                System.getenv(WINDOWS_APPDATA_ENV)
        );
    }

    static StoragePaths defaultPaths(String osName, String userHome, String xdgConfigHome, String windowsAppData) {
        if (Strings.CI.contains(osName, "win")) {
            Path windowsConfigHome = StringUtils.isNotBlank(windowsAppData)
                    ? Path.of(windowsAppData)
                    : Path.of(userHome, "AppData", "Roaming");
            return new StoragePaths(windowsConfigHome);
        }
        return StringUtils.isNotBlank(xdgConfigHome)
            ? new StoragePaths(Path.of(xdgConfigHome))
            : new StoragePaths(Path.of(userHome, ".config"));
    }

    public Path appConfigDirectory() {
        return configHome.resolve(APP_NAME);
    }

    public Path databaseDirectory() {
        return appConfigDirectory().resolve("data");
    }

    public Path databaseFilePrefix() {
        return h2DatabaseFilePrefix();
    }

    public Path databaseFile() {
        return h2DatabaseFile();
    }

    public Path h2DatabaseFilePrefix() {
        return databaseDirectory().resolve(DB_NAME);
    }

    public Path h2MigratingDatabaseFilePrefix() {
        return databaseDirectory().resolve(H2_MIGRATING_DB_NAME);
    }

    public Path h2DatabaseFile() {
        return databaseDirectory().resolve("%s.mv.db".formatted(DB_NAME));
    }

    public Path h2MigratingDatabaseFile() {
        return databaseDirectory().resolve("%s.mv.db".formatted(H2_MIGRATING_DB_NAME));
    }

    public Path sqliteDatabaseFile() {
        return databaseDirectory().resolve(SQLITE_DB_FILE_NAME);
    }

    public Path sqliteMigratingDatabaseFile() {
        return databaseDirectory().resolve(SQLITE_MIGRATING_DB_FILE_NAME);
    }

    public Path backupsDirectory() {
        return databaseDirectory().resolve("backups");
    }

    public Path attachmentsDirectory() {
        return appConfigDirectory().resolve("attachments");
    }

    public Path jcefBundleDirectory() {
        return appConfigDirectory().resolve("jcef-bundle");
    }

    public Path settingsFile() {
        return appConfigDirectory().resolve("chat4j.properties");
    }

    public Path modelsCacheDirectory() {
        return appConfigDirectory().resolve("models-cache");
    }

    public String jdbcUrl() {
        return h2JdbcUrl(false);
    }

    public String h2JdbcUrl(boolean migrating) {
        Path filePrefix = migrating ? h2MigratingDatabaseFilePrefix() : h2DatabaseFilePrefix();
        return "jdbc:h2:file:%s".formatted(filePrefix);
    }

    public String sqliteJdbcUrl(boolean migrating) {
        Path databaseFile = migrating ? sqliteMigratingDatabaseFile() : sqliteDatabaseFile();
        return "jdbc:sqlite:%s".formatted(databaseFile.toAbsolutePath());
    }
}
