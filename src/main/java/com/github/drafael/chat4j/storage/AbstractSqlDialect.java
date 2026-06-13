package com.github.drafael.chat4j.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

abstract class AbstractSqlDialect implements SqlDialect {

    @Override
    public void ensureConversationWebSearchColumns(Connection connection) throws SQLException {
        ensureColumn(
                connection,
                "conversations",
                "web_search_enabled",
                webSearchEnabledColumnDefinition()
        );
        ensureColumn(
                connection,
                "conversations",
                "web_search_option",
                webSearchOptionColumnDefinition()
        );
    }

    protected abstract String webSearchEnabledColumnDefinition();

    protected abstract String webSearchOptionColumnDefinition();

    private void ensureColumn(Connection connection, String tableName, String columnName, String columnDefinition)
            throws SQLException {
        if (columnExists(connection, tableName, columnName)) {
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "ALTER TABLE %s ADD COLUMN %s".formatted(tableName, columnDefinition)
        )) {
            statement.executeUpdate();
        }
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
            if (columns.next()) {
                return true;
            }
        }

        try (ResultSet columns = connection.getMetaData().getColumns(
                null,
                null,
                tableName.toUpperCase(),
                columnName.toUpperCase()
        )) {
            return columns.next();
        }
    }
}
