package com.github.drafael.chat4j.storage;

import org.apache.commons.lang3.StringUtils;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;

public final class H2DataSourceFactory {

    private static final String CREDENTIALS_FILE_NAME = "db.credentials";
    private static final String USER_KEY = "user";
    private static final String PASSWORD_KEY = "password";
    private static final String DEFAULT_USER = "sa";
    private static final int PASSWORD_BYTES = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    private H2DataSourceFactory() {
    }

    public static DataSource create(StoragePaths storagePaths) {
        DatabaseCredentials credentials = resolveDatabaseCredentials(storagePaths);
        return createDataSource(storagePaths.jdbcUrl(), credentials.user(), credentials.password());
    }

    private static DatabaseCredentials resolveDatabaseCredentials(StoragePaths storagePaths) {
        Path credentialsFile = credentialsFile(storagePaths);
        DatabaseCredentials fromFile = readCredentials(credentialsFile);
        if (fromFile != null) {
            return fromFile;
        }

        if (Files.exists(storagePaths.databaseFile())) {
            return migrateLegacyCredentials(storagePaths, credentialsFile);
        }

        DatabaseCredentials generated = new DatabaseCredentials(DEFAULT_USER, generatePassword());
        writeCredentials(credentialsFile, generated);
        return generated;
    }

    private static DatabaseCredentials migrateLegacyCredentials(StoragePaths storagePaths, Path credentialsFile) {
        String generatedPassword = generatePassword();
        DataSource legacyDataSource = createDataSource(storagePaths.jdbcUrl(), DEFAULT_USER, "");

        try (Connection connection = legacyDataSource.getConnection();
             Statement statement = connection.createStatement()
        ) {
            statement.execute("ALTER USER SA SET PASSWORD '%s'".formatted(generatedPassword));
            DatabaseCredentials migrated = new DatabaseCredentials(DEFAULT_USER, generatedPassword);
            writeCredentials(credentialsFile, migrated);
            return migrated;
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to upgrade local database credentials. If this database was already secured, restore %s"
                            .formatted(credentialsFile),
                    e
            );
        }
    }

    private static JdbcDataSource createDataSource(String jdbcUrl, String user, String password) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(jdbcUrl);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        return dataSource;
    }

    private static DatabaseCredentials readCredentials(Path credentialsFile) {
        if (!Files.exists(credentialsFile)) {
            return null;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(credentialsFile)) {
            properties.load(input);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read database credentials file: %s".formatted(credentialsFile), e);
        }

        String user = StringUtils.defaultIfBlank(properties.getProperty(USER_KEY), DEFAULT_USER).trim();
        String password = StringUtils.trimToEmpty(properties.getProperty(PASSWORD_KEY));
        return new DatabaseCredentials(user, password);
    }

    private static void writeCredentials(Path credentialsFile, DatabaseCredentials credentials) {
        try {
            Path parent = credentialsFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Properties properties = new Properties();
            properties.setProperty(USER_KEY, credentials.user());
            properties.setProperty(PASSWORD_KEY, credentials.password());

            try (OutputStream output = Files.newOutputStream(credentialsFile)) {
                properties.store(output, "Chat4J local database credentials");
            }

            applyOwnerOnlyPermissions(credentialsFile);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist database credentials file: %s".formatted(credentialsFile), e);
        }
    }

    private static void applyOwnerOnlyPermissions(Path credentialsFile) {
        try {
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(credentialsFile, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystems (e.g. Windows) do not support this API.
        }
    }

    private static Path credentialsFile(StoragePaths storagePaths) {
        return storagePaths.appConfigDirectory().resolve(CREDENTIALS_FILE_NAME);
    }

    private static String generatePassword() {
        byte[] bytes = new byte[PASSWORD_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private record DatabaseCredentials(String user, String password) {
    }
}
