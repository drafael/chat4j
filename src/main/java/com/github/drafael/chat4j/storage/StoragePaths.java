package com.github.drafael.chat4j.storage;

import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;

public final class StoragePaths {

    private static final String APP_NAME = "chat4j";
    private static final String DB_NAME = "chat4j";

    private final Path configHome;

    private StoragePaths(Path configHome) {
        this.configHome = configHome;
    }

    public static StoragePaths defaultPaths() {
        String xdgConfigHome = System.getenv("XDG_CONFIG_HOME");
        if (StringUtils.isNotBlank(xdgConfigHome)) {
            return new StoragePaths(Path.of(xdgConfigHome));
        }
        return new StoragePaths(Path.of(System.getProperty("user.home"), ".config"));
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
        return databaseDirectory().resolve(DB_NAME + ".mv.db");
    }

    public Path modelsCacheDirectory() {
        return appConfigDirectory().resolve("models-cache");
    }

    public String jdbcUrl() {
        return "jdbc:h2:file:" + databaseFilePrefix() + ";AUTO_SERVER=TRUE";
    }
}
