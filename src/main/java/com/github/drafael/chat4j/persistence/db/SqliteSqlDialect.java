package com.github.drafael.chat4j.persistence.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.apache.commons.lang3.StringUtils.isBlank;

public final class SqliteSqlDialect extends AbstractSqlDialect {

    @Override
    public StorageBackend backend() {
        return StorageBackend.SQLITE;
    }

    @Override
    public String migrationLocation() {
        return "classpath:db/migration/sqlite";
    }

    @Override
    public void bindUuid(PreparedStatement statement, int parameterIndex, UUID value) throws SQLException {
        if (value == null) {
            statement.setString(parameterIndex, null);
            return;
        }
        statement.setString(parameterIndex, value.toString());
    }

    @Override
    public UUID readUuid(ResultSet resultSet, String columnLabel) throws SQLException {
        String value = resultSet.getString(columnLabel);
        return isBlank(value) ? null : UUID.fromString(value);
    }

    @Override
    public String booleanToggleExpression(String columnName) {
        return "CASE WHEN %s THEN 0 ELSE 1 END".formatted(columnName);
    }

    @Override
    public String attachmentUpsertSql() {
        return """
                INSERT INTO attachments (id, storage_path, original_name, mime_type, size_bytes, sha256)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    storage_path = excluded.storage_path,
                    original_name = excluded.original_name,
                    mime_type = excluded.mime_type,
                    size_bytes = excluded.size_bytes,
                    sha256 = excluded.sha256
                """;
    }

    @Override
    public String substringExpression(String expression, int start, int length) {
        return "substr(%s, %d, %d)".formatted(expression, start, length);
    }

    @Override
    protected String webSearchEnabledColumnDefinition() {
        return "web_search_enabled INTEGER DEFAULT 0";
    }

    @Override
    protected String webSearchOptionColumnDefinition() {
        return "web_search_option TEXT";
    }
}
