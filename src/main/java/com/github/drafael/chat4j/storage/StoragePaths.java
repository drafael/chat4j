package com.github.drafael.chat4j.storage;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.nio.file.Path;

public final class StoragePaths {

    private static final String APP_NAME = "chat4j";
    private static final String DB_NAME = "chat4j";
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

        if (StringUtils.isNotBlank(xdgConfigHome)) {
            return new StoragePaths(Path.of(xdgConfigHome));
        }

        return new StoragePaths(Path.of(userHome, ".config"));
    }

    public Path appConfigDirectory() {
        return configHome.resolve(APP_NAME);
    }

    public Path databaseDirectory() {
        return appConfigDirectory().resolve("data");
    }

    public Path databaseFilePrefix() {
        return databaseDirectory().resolve(DB_NAME);
    }

    public Path databaseFile() {
        return databaseDirectory().resolve("%s.mv.db".formatted(DB_NAME));
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
        return "jdbc:h2:file:%s".formatted(databaseFilePrefix());
    }
}
