package com.github.drafael.chat4j.storage;

public final class SqlDialects {

    private static final SqlDialect H2 = new H2SqlDialect();
    private static final SqlDialect SQLITE = new SqliteSqlDialect();

    private SqlDialects() {
    }

    public static SqlDialect forBackend(StorageBackend backend) {
        return switch (backend) {
            case H2 -> H2;
            case SQLITE -> SQLITE;
        };
    }
}
