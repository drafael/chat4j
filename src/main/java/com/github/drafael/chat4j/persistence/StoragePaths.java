package com.github.drafael.chat4j.persistence;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;

public final class StoragePaths {

    private static final String APP_NAME = "chat4j";
    private static final String DB_NAME = "chat4j";
    private static final String SQLITE_DB_FILE_NAME = "chat4j.sqlite3";
    private static final String SQLITE_MIGRATING_DB_FILE_NAME = "chat4j.sqlite3.migrating";
    private static final String H2_MIGRATING_DB_NAME = "chat4j-h2-migrating";
    private static final String WINDOWS_APPDATA_ENV = "APPDATA";

    private final Path configHome;
    private final Path dataHome;
    private final Path cacheHome;
    private final Path stateHome;
    private final Path legacyOAuthConfigHome;

    private StoragePaths(
            Path configHome,
            Path dataHome,
            Path cacheHome,
            Path stateHome,
            Path legacyOAuthConfigHome
    ) {
        this.configHome = configHome;
        this.dataHome = dataHome;
        this.cacheHome = cacheHome;
        this.stateHome = stateHome;
        this.legacyOAuthConfigHome = legacyOAuthConfigHome;
    }

    /**
     * Creates an isolated layout with one base home. Retained for compatibility with embedded and test callers.
     */
    public static StoragePaths ofConfigHome(@NonNull Path baseHome) {
        return ofBaseHomes(baseHome, baseHome, baseHome, baseHome);
    }

    public static StoragePaths ofBaseHomes(
            @NonNull Path configHome,
            @NonNull Path dataHome,
            @NonNull Path cacheHome,
            @NonNull Path stateHome
    ) {
        return new StoragePaths(configHome, dataHome, cacheHome, stateHome, configHome);
    }

    public static StoragePaths defaultPaths() {
        return defaultPaths(
                SystemUtils.IS_OS_WINDOWS,
                System.getProperty("user.home"),
                System.getenv("XDG_CONFIG_HOME"),
                System.getenv("XDG_DATA_HOME"),
                System.getenv("XDG_CACHE_HOME"),
                System.getenv("XDG_STATE_HOME"),
                System.getenv(WINDOWS_APPDATA_ENV)
        );
    }

    static StoragePaths defaultPaths(
            boolean windows,
            String userHome,
            String xdgConfigHome,
            String windowsAppData
    ) {
        return defaultPaths(windows, userHome, xdgConfigHome, null, null, null, windowsAppData);
    }

    static StoragePaths defaultPaths(
            boolean windows,
            String userHome,
            String xdgConfigHome,
            String xdgDataHome,
            String xdgCacheHome,
            String xdgStateHome,
            String windowsAppData
    ) {
        if (windows) {
            Path windowsHome = StringUtils.isNotBlank(windowsAppData)
                    ? Path.of(windowsAppData)
                    : requiredHomePath(userHome, "AppData", "Roaming");
            return new StoragePaths(windowsHome, windowsHome, windowsHome, windowsHome, windowsHome);
        }

        Path fallbackConfigHome = requiredHomePath(userHome, ".config");
        Path legacyOAuthConfigHome = xdgHome(xdgConfigHome, fallbackConfigHome);
        Path configHome = xdgHome(xdgConfigHome, fallbackConfigHome);
        Path dataHome = xdgHome(xdgDataHome, requiredHomePath(userHome, ".local", "share"));
        Path cacheHome = xdgHome(xdgCacheHome, requiredHomePath(userHome, ".cache"));
        Path stateHome = xdgHome(xdgStateHome, requiredHomePath(userHome, ".local", "state"));
        return new StoragePaths(configHome, dataHome, cacheHome, stateHome, legacyOAuthConfigHome);
    }

    public Path appConfigDirectory() {
        return configHome.resolve(APP_NAME);
    }

    public Path appDataDirectory() {
        return dataHome.resolve(APP_NAME);
    }

    public Path appCacheDirectory() {
        return cacheHome.resolve(APP_NAME);
    }

    public Path appStateDirectory() {
        return stateHome.resolve(APP_NAME);
    }

    public Path databaseDirectory() {
        return appDataDirectory().resolve("data");
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
        return appDataDirectory().resolve("attachments");
    }

    public Path jcefBundleDirectory() {
        return appCacheDirectory().resolve("jcef-bundle");
    }

    public Path settingsFile() {
        return appConfigDirectory().resolve("chat4j.properties");
    }

    public Path promptsFile() {
        return appDataDirectory().resolve("prompts.json");
    }

    public Path mcpFile() {
        return appConfigDirectory().resolve("mcp.json");
    }

    public Path secretsDirectory() {
        return appDataDirectory().resolve("secrets");
    }

    public Path tokenVaultFile() {
        return secretsDirectory().resolve("token-vault.json");
    }

    public Path tokenVaultMasterKeyFile() {
        return secretsDirectory().resolve("master.key");
    }

    public Path tokenVaultLockFile() {
        return secretsDirectory().resolve("token-vault.lock");
    }

    public Path copilotAuthFile() {
        return secretsDirectory().resolve("copilot-auth.json");
    }

    public Path codexAuthFile() {
        return secretsDirectory().resolve("codex-auth.json");
    }

    public Path databaseCredentialsFile() {
        return secretsDirectory().resolve("db.credentials");
    }

    /**
     * The single cache root used by normal runtime cache consumers.
     */
    public Path cacheDirectory() {
        return appCacheDirectory().resolve("cache");
    }

    public Path catalogMetadataFile() {
        return appCacheDirectory().resolve("catalog-index.properties");
    }

    /**
     * Legacy cache location. This exists exclusively for deletion-only startup cleanup.
     */
    public Path legacyModelsCacheDirectory() {
        return appConfigDirectory().resolve("models-cache");
    }

    public Path sttModelsDirectory() {
        return appDataDirectory().resolve("stt").resolve("models");
    }

    public Path sttTempDirectory() {
        return appCacheDirectory().resolve("stt").resolve("temp");
    }

    public Path logsDirectory() {
        return appStateDirectory().resolve("logs");
    }

    public Path windowStateFile() {
        return appStateDirectory().resolve("window.properties");
    }

    Path legacyDatabaseDirectory() {
        return appConfigDirectory().resolve("data");
    }

    Path legacyAttachmentsDirectory() {
        return appConfigDirectory().resolve("attachments");
    }

    Path legacyJcefBundleDirectory() {
        return appConfigDirectory().resolve("jcef-bundle");
    }

    Path legacyPromptsFile() {
        return appConfigDirectory().resolve("prompts.json");
    }

    Path legacySecretsDirectory() {
        return appConfigDirectory().resolve("secrets");
    }

    Path legacyDatabaseCredentialsFile() {
        return appConfigDirectory().resolve("db.credentials");
    }

    Path legacyCacheDirectory() {
        return appConfigDirectory().resolve("cache");
    }

    Path legacySttModelsDirectory() {
        return appConfigDirectory().resolve("stt").resolve("models");
    }

    Path legacySttTempDirectory() {
        return appConfigDirectory().resolve("stt").resolve("temp");
    }

    Path legacyLogsDirectory() {
        return appConfigDirectory().resolve("logs");
    }

    Path legacyCopilotAuthFile() {
        return legacyOAuthConfigHome.resolve(APP_NAME).resolve("copilot-auth.json");
    }

    Path legacyCodexAuthFile() {
        return legacyOAuthConfigHome.resolve(APP_NAME).resolve("codex-auth.json");
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

    private static Path xdgHome(String configured, Path fallback) {
        if (StringUtils.isBlank(configured)) {
            return fallback;
        }
        try {
            Path path = Path.of(configured);
            return path.isAbsolute() ? path : fallback;
        } catch (InvalidPathException e) {
            return fallback;
        }
    }

    private static Path requiredHomePath(String userHome, String... children) {
        if (StringUtils.isBlank(userHome)) {
            throw new IllegalStateException("No durable storage home is available");
        }
        return Path.of(userHome, children);
    }
}
