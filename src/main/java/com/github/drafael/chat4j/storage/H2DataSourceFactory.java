package com.github.drafael.chat4j.storage;

import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;

public final class H2DataSourceFactory {

    private H2DataSourceFactory() {}

    public static DataSource create(StoragePaths storagePaths) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(storagePaths.jdbcUrl());
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
