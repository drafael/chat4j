package com.github.drafael.chat4j.persistence.db;

import com.github.drafael.chat4j.persistence.StoragePaths;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class H2DataSourceFactoryTest {

    @Test
    @DisplayName("Factory generates persisted credentials for a new local database")
    void create_whenDatabaseIsNew_generatesAndPersistsCredentials() throws Exception {
        Path tempRoot = Files.createTempDirectory("chat4j-h2-new-");
        StoragePaths storagePaths = createStoragePaths(tempRoot);

        DataSource subject = H2DataSourceFactory.create(storagePaths);

        try (Connection connection = subject.getConnection();
             PreparedStatement statement = connection.prepareStatement("CREATE TABLE demo (id INT PRIMARY KEY)")
        ) {
            statement.execute();
        }

        Path credentialsFile = storagePaths.appConfigDirectory().resolve("db.credentials");
        assertThat(credentialsFile).exists();

        String credentialsContent = Files.readString(credentialsFile, StandardCharsets.UTF_8);
        assertThat(credentialsContent).contains("user=sa");
        assertThat(extractPassword(credentialsContent)).isNotBlank();
    }

    @Test
    @DisplayName("Factory upgrades legacy blank-password database and keeps existing data")
    void create_whenLegacyDatabaseExists_upgradesCredentialsAndKeepsData() throws Exception {
        Path tempRoot = Files.createTempDirectory("chat4j-h2-legacy-");
        StoragePaths storagePaths = createStoragePaths(tempRoot);

        JdbcDataSource legacyDataSource = new JdbcDataSource();
        legacyDataSource.setURL(storagePaths.jdbcUrl());
        legacyDataSource.setUser("sa");
        legacyDataSource.setPassword("");

        try (Connection connection = legacyDataSource.getConnection();
             PreparedStatement createTable = connection.prepareStatement("CREATE TABLE legacy_demo (id INT PRIMARY KEY)")
        ) {
            createTable.execute();
        }

        try (Connection connection = legacyDataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("INSERT INTO legacy_demo (id) VALUES (7)")
        ) {
            insert.executeUpdate();
        }

        DataSource subject = H2DataSourceFactory.create(storagePaths);

        try (Connection connection = subject.getConnection();
             PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM legacy_demo");
             ResultSet rs = query.executeQuery()
        ) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }

        assertThatThrownBy(legacyDataSource::getConnection)
                .isInstanceOf(Exception.class);
    }

    private StoragePaths createStoragePaths(Path configHome) throws Exception {
        Constructor<StoragePaths> constructor = StoragePaths.class.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        return constructor.newInstance(configHome);
    }

    private String extractPassword(String credentialsContent) {
        return credentialsContent.lines()
                .filter(line -> line.startsWith("password="))
                .map(line -> line.substring("password=".length()).trim())
                .findFirst()
                .orElse("");
    }
}
