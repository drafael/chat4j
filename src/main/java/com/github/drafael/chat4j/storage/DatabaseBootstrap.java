package com.github.drafael.chat4j.storage;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;

public class DatabaseBootstrap {

    private final StoragePaths storagePaths;
    private final DataSource dataSource;

    public DatabaseBootstrap(StoragePaths storagePaths, DataSource dataSource) {
        this.storagePaths = storagePaths;
        this.dataSource = dataSource;
    }

    public void init() throws IOException {
        Files.createDirectories(storagePaths.databaseDirectory());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .load()
                .migrate();
    }
}
