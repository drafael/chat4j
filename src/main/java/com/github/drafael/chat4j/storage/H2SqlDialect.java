package com.github.drafael.chat4j.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public final class H2SqlDialect extends AbstractSqlDialect {

    @Override
    public StorageBackend backend() {
        return StorageBackend.H2;
    }

    @Override
    public String migrationLocation() {
        return "classpath:db/migration/h2";
    }

    @Override
    public void bindUuid(PreparedStatement statement, int parameterIndex, UUID value) throws SQLException {
        statement.setObject(parameterIndex, value);
    }

    @Override
    public UUID readUuid(ResultSet resultSet, String columnLabel) throws SQLException {
        return resultSet.getObject(columnLabel, UUID.class);
    }

    @Override
    public String booleanToggleExpression(String columnName) {
        return "NOT %s".formatted(columnName);
    }

    @Override
    public String attachmentUpsertSql() {
        return "MERGE INTO attachments (id, storage_path, original_name, mime_type, size_bytes, sha256) KEY (id) VALUES (?, ?, ?, ?, ?, ?)";
    }

    @Override
    public String substringExpression(String expression, int start, int length) {
        return "SUBSTRING(%s, %d, %d)".formatted(expression, start, length);
    }

    @Override
    protected String webSearchEnabledColumnDefinition() {
        return "web_search_enabled BOOLEAN DEFAULT FALSE";
    }

    @Override
    protected String webSearchOptionColumnDefinition() {
        return "web_search_option VARCHAR(80)";
    }
}
