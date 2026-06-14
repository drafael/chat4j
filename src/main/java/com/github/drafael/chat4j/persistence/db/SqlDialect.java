package com.github.drafael.chat4j.persistence.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public interface SqlDialect {

    StorageBackend backend();

    String migrationLocation();

    void bindUuid(PreparedStatement statement, int parameterIndex, UUID value) throws SQLException;

    UUID readUuid(ResultSet resultSet, String columnLabel) throws SQLException;

    String booleanToggleExpression(String columnName);

    String attachmentUpsertSql();

    String substringExpression(String expression, int start, int length);

    void ensureConversationWebSearchColumns(Connection connection) throws SQLException;
}
