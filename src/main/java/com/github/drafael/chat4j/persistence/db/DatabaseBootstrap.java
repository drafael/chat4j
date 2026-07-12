package com.github.drafael.chat4j.persistence.db;

import com.github.drafael.chat4j.persistence.StoragePaths;
import java.io.IOException;
import java.nio.file.Files;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;

public class DatabaseBootstrap {

    private final StoragePaths storagePaths;
    private final DataSource dataSource;
    private final SqlDialect sqlDialect;

    public DatabaseBootstrap(StoragePaths storagePaths, DataSource dataSource) {
        this(storagePaths, dataSource, new H2SqlDialect());
    }

    public DatabaseBootstrap(StoragePaths storagePaths, DataSource dataSource, SqlDialect sqlDialect) {
        this.storagePaths = storagePaths;
        this.dataSource = dataSource;
        this.sqlDialect = sqlDialect;
    }

    public void init() throws IOException {
        Files.createDirectories(storagePaths.databaseDirectory());

        Flyway.configure()
                .dataSource(dataSource)
                .locations(sqlDialect.migrationLocation())
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .load()
                .migrate();
    }
}
