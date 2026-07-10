package com.github.drafael.chat4j.persistence.migration;

import com.github.drafael.chat4j.persistence.db.ChatStorageSettings;
import com.github.drafael.chat4j.persistence.db.DatabaseBootstrap;
import com.github.drafael.chat4j.persistence.db.PersistenceBackendConfig;
import com.github.drafael.chat4j.persistence.db.PersistenceDataSourceFactory;
import com.github.drafael.chat4j.persistence.db.SqlDialect;
import com.github.drafael.chat4j.persistence.db.SqlDialects;
import com.github.drafael.chat4j.persistence.db.StorageBackend;
import com.github.drafael.chat4j.persistence.db.StoragePaths;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;

@Slf4j
public class PersistenceBackendMigrationService {

    private static final DateTimeFormatter BACKUP_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
    private static final DateTimeFormatter SQLITE_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final StoragePaths storagePaths;
    private final ChatStorageSettings chatStorageSettings;

    public PersistenceBackendMigrationService(@NonNull StoragePaths storagePaths, @NonNull SettingsRepository settingsRepo) {
        this(storagePaths, new ChatStorageSettings(settingsRepo));
    }

    PersistenceBackendMigrationService(@NonNull StoragePaths storagePaths, @NonNull ChatStorageSettings chatStorageSettings) {
        this.storagePaths = storagePaths;
        this.chatStorageSettings = chatStorageSettings;
    }

    public StorageBackend migrateIfNeeded() throws SQLException, IOException {
        if (shouldMigrateExistingH2ToDefaultSqlite()) {
            return migrateAndMarkActive(StorageBackend.H2, StorageBackend.SQLITE);
        }

        PersistenceBackendConfig config = chatStorageSettings.load();
        StorageBackend sourceBackend = config.activeBackend();
        StorageBackend targetBackend = config.pendingMigrationTarget().orElse(null);
        if (targetBackend == null) {
            return sourceBackend;
        }

        return migrateAndMarkActive(sourceBackend, targetBackend);
    }

    private boolean shouldMigrateExistingH2ToDefaultSqlite() throws SQLException {
        return PersistenceBackendConfig.DEFAULT_BACKEND == StorageBackend.SQLITE
                && chatStorageSettings.hasNoStoredBackendSelection()
                && Files.exists(storagePaths.h2DatabaseFile())
                && !Files.exists(storagePaths.sqliteDatabaseFile());
    }

    private StorageBackend migrateAndMarkActive(StorageBackend sourceBackend, StorageBackend targetBackend)
            throws SQLException, IOException {
        log.info("Starting chat storage migration: {} -> {}", sourceBackend, targetBackend);
        try {
            migrate(sourceBackend, targetBackend);
            chatStorageSettings.markActive(targetBackend);
            log.info("Completed chat storage migration: {} -> {}", sourceBackend, targetBackend);
            return targetBackend;
        } catch (SQLException | IOException | RuntimeException e) {
            log.warn("Chat storage migration failed: {}", ExceptionUtils.getMessage(e));
            cleanupStagedFilesSafely(targetBackend, e);
            throw e;
        }
    }

    private void migrate(StorageBackend sourceBackend, StorageBackend targetBackend) throws SQLException, IOException {
        Files.createDirectories(storagePaths.databaseDirectory());
        cleanupStagedFiles(targetBackend);

        SqlDialect sourceDialect = SqlDialects.forBackend(sourceBackend);
        SqlDialect targetDialect = SqlDialects.forBackend(targetBackend);
        DataSource sourceDataSource = PersistenceDataSourceFactory.create(storagePaths, sourceBackend);
        DataSource targetDataSource = PersistenceDataSourceFactory.create(storagePaths, targetBackend, true);

        new DatabaseBootstrap(storagePaths, sourceDataSource, sourceDialect).init();
        new DatabaseBootstrap(storagePaths, targetDataSource, targetDialect).init();

        copyData(sourceDataSource, sourceDialect, targetDataSource, targetDialect);
        verifyCounts(sourceDataSource, targetDataSource);
        shutdownIfH2(targetBackend, targetDataSource);
        promoteStagedDatabase(targetBackend);
    }

    private void copyData(
            DataSource sourceDataSource,
            SqlDialect sourceDialect,
            DataSource targetDataSource,
            SqlDialect targetDialect
    ) throws SQLException {
        try (Connection source = sourceDataSource.getConnection();
             Connection target = targetDataSource.getConnection()
        ) {
            target.setAutoCommit(false);
            try {
                copyConversations(source, sourceDialect, target, targetDialect);
                copyMessages(source, sourceDialect, target, targetDialect);
                copyAttachments(source, sourceDialect, target, targetDialect);
                copyMessageAttachments(source, sourceDialect, target, targetDialect);
                target.commit();
            } catch (SQLException | RuntimeException e) {
                rollbackSafely(target, e);
                throw e;
            }
        }
    }

    private void copyConversations(
            Connection source,
            SqlDialect sourceDialect,
            Connection target,
            SqlDialect targetDialect
    ) throws SQLException {
        try (PreparedStatement select = source.prepareStatement(
                """
                SELECT id, title, provider, model, is_favorite, created_at, updated_at,
                       agent_mode_enabled, agent_project_root, reasoning_level,
                       web_search_enabled, web_search_option
                FROM conversations
                ORDER BY created_at, id
                """
        );
             ResultSet rows = select.executeQuery();
             PreparedStatement insert = target.prepareStatement(
                """
                INSERT INTO conversations (
                    id, title, provider, model, is_favorite, created_at, updated_at,
                    agent_mode_enabled, agent_project_root, reasoning_level,
                    web_search_enabled, web_search_option
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
             )
        ) {
            while (rows.next()) {
                targetDialect.bindUuid(insert, 1, sourceDialect.readUuid(rows, "id"));
                insert.setString(2, rows.getString("title"));
                insert.setString(3, rows.getString("provider"));
                insert.setString(4, rows.getString("model"));
                insert.setBoolean(5, rows.getBoolean("is_favorite"));
                bindTimestamp(targetDialect, insert, 6, rows.getTimestamp("created_at"));
                bindTimestamp(targetDialect, insert, 7, rows.getTimestamp("updated_at"));
                insert.setBoolean(8, rows.getBoolean("agent_mode_enabled"));
                insert.setString(9, rows.getString("agent_project_root"));
                insert.setString(10, rows.getString("reasoning_level"));
                insert.setBoolean(11, rows.getBoolean("web_search_enabled"));
                insert.setString(12, rows.getString("web_search_option"));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void copyMessages(
            Connection source,
            SqlDialect sourceDialect,
            Connection target,
            SqlDialect targetDialect
    ) throws SQLException {
        try (PreparedStatement select = source.prepareStatement(
                """
                SELECT id, conversation_id, role, content, content_json, meta_json, created_at
                FROM messages
                ORDER BY created_at, id
                """
        );
             ResultSet rows = select.executeQuery();
             PreparedStatement insert = target.prepareStatement(
                """
                INSERT INTO messages (id, conversation_id, role, content, content_json, meta_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """
             )
        ) {
            while (rows.next()) {
                targetDialect.bindUuid(insert, 1, sourceDialect.readUuid(rows, "id"));
                targetDialect.bindUuid(insert, 2, sourceDialect.readUuid(rows, "conversation_id"));
                insert.setString(3, rows.getString("role"));
                insert.setString(4, rows.getString("content"));
                insert.setString(5, rows.getString("content_json"));
                insert.setString(6, rows.getString("meta_json"));
                bindTimestamp(targetDialect, insert, 7, rows.getTimestamp("created_at"));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void copyAttachments(
            Connection source,
            SqlDialect sourceDialect,
            Connection target,
            SqlDialect targetDialect
    ) throws SQLException {
        try (PreparedStatement select = source.prepareStatement(
                """
                SELECT id, storage_path, original_name, mime_type, size_bytes, sha256, created_at
                FROM attachments
                ORDER BY created_at, id
                """
        );
             ResultSet rows = select.executeQuery();
             PreparedStatement insert = target.prepareStatement(
                """
                INSERT INTO attachments (id, storage_path, original_name, mime_type, size_bytes, sha256, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """
             )
        ) {
            while (rows.next()) {
                targetDialect.bindUuid(insert, 1, sourceDialect.readUuid(rows, "id"));
                insert.setString(2, rows.getString("storage_path"));
                insert.setString(3, rows.getString("original_name"));
                insert.setString(4, rows.getString("mime_type"));
                insert.setLong(5, rows.getLong("size_bytes"));
                insert.setString(6, rows.getString("sha256"));
                bindTimestamp(targetDialect, insert, 7, rows.getTimestamp("created_at"));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void copyMessageAttachments(
            Connection source,
            SqlDialect sourceDialect,
            Connection target,
            SqlDialect targetDialect
    ) throws SQLException {
        try (PreparedStatement select = source.prepareStatement(
                """
                SELECT message_id, attachment_id, part_index
                FROM message_attachments
                ORDER BY message_id, part_index
                """
        );
             ResultSet rows = select.executeQuery();
             PreparedStatement insert = target.prepareStatement(
                """
                INSERT INTO message_attachments (message_id, attachment_id, part_index)
                VALUES (?, ?, ?)
                """
             )
        ) {
            while (rows.next()) {
                targetDialect.bindUuid(insert, 1, sourceDialect.readUuid(rows, "message_id"));
                targetDialect.bindUuid(insert, 2, sourceDialect.readUuid(rows, "attachment_id"));
                insert.setInt(3, rows.getInt("part_index"));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void bindTimestamp(
            SqlDialect targetDialect,
            PreparedStatement statement,
            int parameterIndex,
            Timestamp timestamp
    ) throws SQLException {
        if (targetDialect.backend() != StorageBackend.SQLITE || timestamp == null) {
            statement.setTimestamp(parameterIndex, timestamp);
            return;
        }

        statement.setString(parameterIndex, SQLITE_TIMESTAMP_FORMAT.format(timestamp.toLocalDateTime()));
    }

    private void verifyCounts(DataSource sourceDataSource, DataSource targetDataSource) throws SQLException {
        for (String tableName : List.of("conversations", "messages", "attachments", "message_attachments")) {
            long sourceCount = countRows(sourceDataSource, tableName);
            long targetCount = countRows(targetDataSource, tableName);
            if (sourceCount != targetCount) {
                throw new IllegalStateException(
                        "Migration verification failed for %s: source=%d target=%d"
                                .formatted(tableName, sourceCount, targetCount)
                );
            }
        }
    }

    private long countRows(DataSource dataSource, String tableName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM %s".formatted(tableName));
             ResultSet rows = statement.executeQuery()
        ) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private void promoteStagedDatabase(StorageBackend targetBackend) throws IOException {
        backupExistingTarget(targetBackend);
        switch (targetBackend) {
            case H2 -> Files.move(
                    storagePaths.h2MigratingDatabaseFile(),
                    storagePaths.h2DatabaseFile(),
                    StandardCopyOption.REPLACE_EXISTING
            );
            case SQLITE -> Files.move(
                    storagePaths.sqliteMigratingDatabaseFile(),
                    storagePaths.sqliteDatabaseFile(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private void backupExistingTarget(StorageBackend targetBackend) throws IOException {
        String timestamp = BACKUP_TIMESTAMP_FORMAT.format(LocalDateTime.now());
        Files.createDirectories(storagePaths.backupsDirectory());
        switch (targetBackend) {
            case H2 -> {
                backupIfExists(storagePaths.h2DatabaseFile(), "chat4j.mv.db.%s.bak".formatted(timestamp));
                backupIfExists(
                        storagePaths.databaseDirectory().resolve("chat4j.trace.db"),
                        "chat4j.trace.db.%s.bak".formatted(timestamp)
                );
                backupIfExists(
                        storagePaths.databaseDirectory().resolve("chat4j.lock.db"),
                        "chat4j.lock.db.%s.bak".formatted(timestamp)
                );
            }
            case SQLITE -> {
                Path sqliteDatabaseFile = storagePaths.sqliteDatabaseFile();
                backupIfExists(sqliteDatabaseFile, "chat4j.sqlite3.%s.bak".formatted(timestamp));
                backupIfExists(
                        sqliteSidecarFile(sqliteDatabaseFile, "-journal"),
                        "chat4j.sqlite3-journal.%s.bak".formatted(timestamp)
                );
                backupIfExists(
                        sqliteSidecarFile(sqliteDatabaseFile, "-wal"),
                        "chat4j.sqlite3-wal.%s.bak".formatted(timestamp)
                );
                backupIfExists(
                        sqliteSidecarFile(sqliteDatabaseFile, "-shm"),
                        "chat4j.sqlite3-shm.%s.bak".formatted(timestamp)
                );
            }
        }
    }

    private void backupIfExists(Path source, String backupFileName) throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        Files.move(source, storagePaths.backupsDirectory().resolve(backupFileName), StandardCopyOption.REPLACE_EXISTING);
    }

    private void cleanupStagedFilesSafely(StorageBackend backend, Throwable error) {
        try {
            cleanupStagedFiles(backend);
        } catch (IOException e) {
            error.addSuppressed(e);
            log.warn("Failed to clean staged chat storage migration files: {}", ExceptionUtils.getMessage(e));
        }
    }

    private void cleanupStagedFiles(StorageBackend backend) throws IOException {
        switch (backend) {
            case H2 -> {
                deleteIfExists(storagePaths.h2MigratingDatabaseFile());
                deleteIfExists(storagePaths.databaseDirectory().resolve("chat4j-h2-migrating.trace.db"));
                deleteIfExists(storagePaths.databaseDirectory().resolve("chat4j-h2-migrating.lock.db"));
            }
            case SQLITE -> {
                Path sqliteMigratingDatabaseFile = storagePaths.sqliteMigratingDatabaseFile();
                deleteIfExists(sqliteMigratingDatabaseFile);
                deleteIfExists(sqliteSidecarFile(sqliteMigratingDatabaseFile, "-journal"));
                deleteIfExists(sqliteSidecarFile(sqliteMigratingDatabaseFile, "-wal"));
                deleteIfExists(sqliteSidecarFile(sqliteMigratingDatabaseFile, "-shm"));
            }
        }
    }

    private Path sqliteSidecarFile(Path databaseFile, String suffix) {
        return databaseFile.resolveSibling("%s%s".formatted(databaseFile.getFileName(), suffix));
    }

    private void deleteIfExists(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    private void shutdownIfH2(StorageBackend backend, DataSource dataSource) {
        if (backend != StorageBackend.H2) {
            return;
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()
        ) {
            statement.execute("SHUTDOWN");
        } catch (SQLException e) {
            log.debug("H2 staged database shutdown reported: {}", ExceptionUtils.getMessage(e));
        }
    }

    private void rollbackSafely(Connection connection, Throwable error) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            error.addSuppressed(e);
        }
    }
}
