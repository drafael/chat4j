package com.github.drafael.chat4j.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseBootstrapTest {

    private StoragePaths storagePaths;
    private DataSource dataSource;
    private DatabaseBootstrap subject;

    @BeforeEach
    void setUp() throws IOException {
        storagePaths = StoragePaths.defaultPaths();
        cleanStorage();
        dataSource = H2DataSourceFactory.create(storagePaths);
        subject = new DatabaseBootstrap(storagePaths, dataSource);
    }

    @AfterEach
    void tearDown() throws IOException {
        cleanStorage();
    }

    @Test
    @DisplayName("Initialisation creates the database file and expected schema tables")
    void init_whenDatabaseIsMissing_createsDatabaseAndSchemaTables() throws Exception {
        subject.init();

        assertThat(storagePaths.databaseFile()).exists();

        try (Connection connection = dataSource.getConnection()) {
            assertThat(tableExists(connection, "conversations")).isTrue();
            assertThat(tableExists(connection, "messages")).isTrue();
            assertThat(tableExists(connection, "provider_configs")).isTrue();
            assertThat(tableExists(connection, "settings")).isTrue();
            assertThat(tableExists(connection, "flyway_schema_history")).isTrue();
        }
    }

    @Test
    @DisplayName("Initialisation can run multiple times while keeping a single applied V1 migration")
    void init_whenInvokedMultipleTimes_appliesInitialMigrationOnlyOnce() throws Exception {
        subject.init();
        subject.init();

        try (Connection connection = dataSource.getConnection()) {
            assertThat(appliedMigrations(connection, "1")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Initialisation baselines a non-empty legacy schema and still applies V1")
    void init_whenLegacySchemaExistsWithoutHistoryTable_baselinesAndAppliesV1() throws Exception {
        createLegacySchemaWithoutHistoryTable();

        subject.init();

        try (Connection connection = dataSource.getConnection()) {
            assertThat(tableExists(connection, "flyway_schema_history")).isTrue();
            assertThat(appliedMigrations(connection, "1")).isEqualTo(1);
            assertThat(tableExists(connection, "conversations")).isTrue();
            assertThat(tableExists(connection, "messages")).isTrue();
            assertThat(tableExists(connection, "provider_configs")).isTrue();
            assertThat(tableExists(connection, "settings")).isTrue();
        }
    }

    @Test
    @DisplayName("Conversation repository persists and reads data when bootstrap has initialised schema")
    void createConversation_whenSchemaIsInitialized_persistsAndLoadsConversation() throws Exception {
        subject.init();
        var conversationRepo = new ConversationRepo(dataSource);

        var conversationId = conversationRepo.createConversation("Hello", "OpenAI", "gpt-4o");
        var conversation = conversationRepo.findById(conversationId);

        assertThat(conversation).isPresent();
        assertThat(conversation).get().extracting(ConversationRepo.ConversationRecord::title).isEqualTo("Hello");
        assertThat(conversation).get().extracting(ConversationRepo.ConversationRecord::provider).isEqualTo("OpenAI");
        assertThat(conversation).get().extracting(ConversationRepo.ConversationRecord::model).isEqualTo("gpt-4o");
    }

    private void createLegacySchemaWithoutHistoryTable() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()
        ) {
            statement.execute("CREATE TABLE legacy_dummy (id INT PRIMARY KEY)");
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(null, null, null, new String[]{"TABLE"})) {
            while (tables.next()) {
                String existingTableName = tables.getString("TABLE_NAME");
                if (tableName.equalsIgnoreCase(existingTableName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int appliedMigrations(Connection connection, String version) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = ? AND \"success\" = TRUE"
        )
        ) {
            statement.setString(1, version);
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void cleanStorage() throws IOException {
        deleteRecursively(storagePaths.appConfigDirectory());
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }

        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
