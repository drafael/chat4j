package com.github.drafael.chat4j.persistence.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public final class H2SqlDialect implements SqlDialect {

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
    public String substringExpression(String expression, int start, int length) {
        return "SUBSTRING(%s, %d, %d)".formatted(expression, start, length);
    }

}
