package com.github.drafael.chat4j.persistence.db;

import javax.sql.DataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

public final class PersistenceDataSourceFactory {

    private PersistenceDataSourceFactory() {
    }

    public static DataSource create(StoragePaths storagePaths, StorageBackend backend) {
        return create(storagePaths, backend, false);
    }

    public static DataSource create(StoragePaths storagePaths, StorageBackend backend, boolean migrating) {
        return switch (backend) {
            case H2 -> H2DataSourceFactory.create(storagePaths, migrating);
            case SQLITE -> createSqlite(storagePaths, migrating);
        };
    }

    private static DataSource createSqlite(StoragePaths storagePaths, boolean migrating) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);

        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl(storagePaths.sqliteJdbcUrl(migrating));
        return dataSource;
    }
}
