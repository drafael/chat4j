package com.github.drafael.chat4j.persistence.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.apache.commons.lang3.StringUtils.isBlank;

public final class SqliteSqlDialect implements SqlDialect {

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
    public String substringExpression(String expression, int start, int length) {
        return "substr(%s, %d, %d)".formatted(expression, start, length);
    }

}
