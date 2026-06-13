package com.github.drafael.chat4j.storage;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;

public final class ChatDataSourceFactory {

    private ChatDataSourceFactory() {
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
