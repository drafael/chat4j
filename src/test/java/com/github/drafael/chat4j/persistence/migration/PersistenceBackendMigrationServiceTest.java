package com.github.drafael.chat4j.persistence.migration;

import com.github.drafael.chat4j.persistence.conversation.ConversationHistoryEntry;
import com.github.drafael.chat4j.persistence.conversation.ConversationRepository;
import com.github.drafael.chat4j.persistence.db.DatabaseBootstrap;
import com.github.drafael.chat4j.persistence.db.PersistenceDataSourceFactory;
import com.github.drafael.chat4j.persistence.db.SqlDialect;
import com.github.drafael.chat4j.persistence.db.SqlDialects;
import com.github.drafael.chat4j.persistence.db.StorageBackend;
import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceBackendMigrationServiceTest {

    private StoragePaths storagePaths;
    private SettingsRepository settingsRepo;

    @BeforeEach
    void setUp() throws Exception {
        storagePaths = StoragePaths.defaultPaths();
        cleanStorage();
        settingsRepo = new SettingsRepository(storagePaths);
    }

    @AfterEach
    void tearDown() throws Exception {
        cleanStorage();
    }

    @Test
    @DisplayName("SQLite repository persists and reads conversations with default timestamps")
    void conversationRepo_whenUsingSqlite_persistsAndReadsConversation() throws Exception {
        DataSource sqliteDataSource = PersistenceDataSourceFactory.create(storagePaths, StorageBackend.SQLITE);
        new DatabaseBootstrap(storagePaths, sqliteDataSource, SqlDialects.forBackend(StorageBackend.SQLITE)).init();
        var subject = new ConversationRepository(
                sqliteDataSource,
                storagePaths.attachmentsDirectory(),
                SqlDialects.forBackend(StorageBackend.SQLITE)
        );

        UUID conversationId = createConversation(subject, "SQLite", "hello from sqlite");

        assertThat(subject.loadConversation(conversationId).map(ConversationRepository.LoadedConversation::conversation)).isPresent();
        assertThat(subject.loadConversation(conversationId).orElseThrow().messages())
                .hasSize(1)
                .first()
                .extracting(record -> record.message().content())
                .isEqualTo("hello from sqlite");
    }

    @Test
    @DisplayName("SQLite repository preserves stable identity through edit, truncate, clear, and first append")
    void conversationRepo_whenUsingSqlite_appliesStableMutationContract() throws Exception {
        DataSource dataSource = PersistenceDataSourceFactory.create(storagePaths, StorageBackend.SQLITE);
        SqlDialect dialect = SqlDialects.forBackend(StorageBackend.SQLITE);
        new DatabaseBootstrap(storagePaths, dataSource, dialect).init();
        var subject = new ConversationRepository(dataSource, storagePaths.attachmentsDirectory(), dialect);
        UUID conversationId = UUID.randomUUID();
        var initialEntry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("initial"));
        var create = new ConversationRepository.CreateConversationCommand(
                conversationId,
                "Initial",
                "OpenAI",
                "gpt-4.1",
                ReasoningLevel.OFF,
                false,
                null,
                false,
                null,
                initialEntry
        );
        subject.createConversation(create);
        subject.createConversation(create);
        subject.appendMessage(
                conversationId,
                new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("suffix"))
        );
        var editedEntry = new ConversationHistoryEntry(
                initialEntry.messageId(),
                initialEntry.ordinal(),
                Message.user("edited")
        );

        subject.updateMessageAndDeleteSuffix(conversationId, editedEntry);

        assertThat(subject.loadConversation(conversationId).orElseThrow().messages())
                .extracting(ConversationRepository.MessageRecord::id)
                .containsExactly(initialEntry.messageId());
        assertThat(subject.loadConversation(conversationId).orElseThrow().messages())
                .extracting(record -> record.message().content())
                .containsExactly("edited");

        subject.clearMessages(conversationId);
        var firstAfterClear = new ConversationRepository.FirstAfterClearCommand(
                conversationId,
                "Restarted",
                new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("restart"))
        );
        subject.appendFirstAfterClear(firstAfterClear);
        subject.appendFirstAfterClear(firstAfterClear);

        ConversationRepository.LoadedConversation loaded = subject.loadConversation(conversationId).orElseThrow();
        assertThat(loaded.conversation().title()).isEqualTo("Restarted");
        assertThat(loaded.messages())
                .extracting(ConversationRepository.MessageRecord::id)
                .containsExactly(firstAfterClear.entry().messageId());
        assertThat(loaded.messages())
                .extracting(ConversationRepository.MessageRecord::ordinal)
                .containsExactly(1);
        assertThat(loaded.messages())
                .extracting(record -> record.message().content())
                .containsExactly("restart");
    }

    @Test
    @DisplayName("SQLite exact-ID deletion spans batches without deleting an unconfirmed conversation")
    void deleteConversations_whenIdsExceedBatchSize_deletesOnlyCapturedIds() throws Exception {
        DataSource dataSource = PersistenceDataSourceFactory.create(storagePaths, StorageBackend.SQLITE);
        new DatabaseBootstrap(storagePaths, dataSource, SqlDialects.forBackend(StorageBackend.SQLITE)).init();
        List<UUID> capturedIds = new ArrayList<>();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO conversations (id, title, provider, model) VALUES (?, ?, ?, ?)"
             )) {
            for (int index = 0; index < 401; index++) {
                UUID id = UUID.randomUUID();
                capturedIds.add(id);
                statement.setString(1, id.toString());
                statement.setString(2, "Captured %d".formatted(index));
                statement.setString(3, "OpenAI");
                statement.setString(4, "gpt-4.1");
                statement.addBatch();
            }
            statement.executeBatch();
        }
        UUID unconfirmedId = UUID.randomUUID();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO conversations (id, title, provider, model) VALUES (?, ?, ?, ?)"
             )) {
            statement.setString(1, unconfirmedId.toString());
            statement.setString(2, "Unconfirmed");
            statement.setString(3, "OpenAI");
            statement.setString(4, "gpt-4.1");
            statement.executeUpdate();
        }
        var subject = new ConversationRepository(
                dataSource,
                storagePaths.attachmentsDirectory(),
                SqlDialects.forBackend(StorageBackend.SQLITE)
        );
        List<UUID> deletionRequest = new ArrayList<>(capturedIds);
        deletionRequest.add(capturedIds.getFirst());
        deletionRequest.add(null);

        subject.deleteConversations(deletionRequest);

        assertThat(subject.conversationsAbsent(capturedIds)).isTrue();
        assertThat(subject.loadConversation(unconfirmedId)).isPresent();
    }

    @Test
    @DisplayName("Migration copies chat data from SQLite to H2 and marks H2 active")
    void migrateIfNeeded_whenPendingIsH2_copiesSqliteDataAndMarksH2Active() throws Exception {
        MigrationFixture fixture = createMigrationFixture(StorageBackend.SQLITE);
        settingsRepo.put("chat.storage.backend.active", StorageBackend.SQLITE.settingValue());
        settingsRepo.put("chat.storage.backend.pending", StorageBackend.H2.settingValue());
        var subject = new PersistenceBackendMigrationService(storagePaths, settingsRepo);
        TimeZone originalTimeZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            StorageBackend activeBackend = subject.migrateIfNeeded();

            assertThat(activeBackend).isEqualTo(StorageBackend.H2);
            assertThat(settingsRepo.get("chat.storage.backend.active")).contains("h2");
            assertThat(settingsRepo.get("chat.storage.backend.pending")).isEmpty();
            assertThat(storagePaths.h2DatabaseFile()).exists();

            DataSource h2DataSource = PersistenceDataSourceFactory.create(storagePaths, StorageBackend.H2);
            var h2Repo = new ConversationRepository(
                    h2DataSource,
                    storagePaths.attachmentsDirectory(),
                    SqlDialects.forBackend(StorageBackend.H2)
            );
            assertMigrationFixture(
                    h2DataSource,
                    SqlDialects.forBackend(StorageBackend.H2),
                    h2Repo,
                    fixture
            );
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    @Test
    @DisplayName("Migration copies chat data from H2 to SQLite and marks SQLite active")
    void migrateIfNeeded_whenPendingIsSqlite_copiesH2DataAndMarksSqliteActive() throws Exception {
        MigrationFixture fixture = createMigrationFixture(StorageBackend.H2);
        settingsRepo.put("chat.storage.backend.active", StorageBackend.H2.settingValue());
        settingsRepo.put("chat.storage.backend.pending", StorageBackend.SQLITE.settingValue());
        var subject = new PersistenceBackendMigrationService(storagePaths, settingsRepo);
        TimeZone originalTimeZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            StorageBackend activeBackend = subject.migrateIfNeeded();

            assertThat(activeBackend).isEqualTo(StorageBackend.SQLITE);
            assertThat(settingsRepo.get("chat.storage.backend.active")).contains("sqlite");
            assertThat(settingsRepo.get("chat.storage.backend.pending")).isEmpty();
            DataSource sqliteDataSource = PersistenceDataSourceFactory.create(storagePaths, StorageBackend.SQLITE);
            var sqliteRepo = new ConversationRepository(
                    sqliteDataSource,
                    storagePaths.attachmentsDirectory(),
                    SqlDialects.forBackend(StorageBackend.SQLITE)
            );
            assertMigrationFixture(
                    sqliteDataSource,
                    SqlDialects.forBackend(StorageBackend.SQLITE),
                    sqliteRepo,
                    fixture
            );
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    @Test
    @DisplayName("Existing unconfigured H2 storage migrates to the SQLite default")
    void migrateIfNeeded_whenDefaultSqliteAndExistingH2Storage_migratesH2DataAndMarksSqliteActive() throws Exception {
        UUID conversationId = createH2Conversation();
        var subject = new PersistenceBackendMigrationService(storagePaths, settingsRepo);

        StorageBackend activeBackend = subject.migrateIfNeeded();

        assertThatSqliteConversationWasMigrated(activeBackend, conversationId);
    }

    @Test
    @DisplayName("Invalid stored active backend blocks automatic existing H2 migration")
    void migrateIfNeeded_whenInvalidActiveBackendIsPresent_doesNotAutoMigrateExistingH2() throws Exception {
        createH2Conversation();
        settingsRepo.put("chat.storage.backend.active", "invalid-backend");
        var subject = new PersistenceBackendMigrationService(storagePaths, settingsRepo);

        StorageBackend activeBackend = subject.migrateIfNeeded();

        assertThat(activeBackend).isEqualTo(StorageBackend.SQLITE);
        assertThat(settingsRepo.get("chat.storage.backend.active")).contains("invalid-backend");
        assertThat(settingsRepo.get("chat.storage.backend.pending")).isEmpty();
        assertThat(storagePaths.sqliteDatabaseFile()).doesNotExist();
        assertThat(storagePaths.h2DatabaseFile()).exists();
    }

    @Test
    @DisplayName("Invalid stored pending backend blocks automatic existing H2 migration")
    void migrateIfNeeded_whenInvalidPendingBackendIsPresent_doesNotAutoMigrateExistingH2() throws Exception {
        createH2Conversation();
        settingsRepo.put("chat.storage.backend.pending", "invalid-backend");
        var subject = new PersistenceBackendMigrationService(storagePaths, settingsRepo);

        StorageBackend activeBackend = subject.migrateIfNeeded();

        assertThat(activeBackend).isEqualTo(StorageBackend.SQLITE);
        assertThat(settingsRepo.get("chat.storage.backend.active")).isEmpty();
        assertThat(settingsRepo.get("chat.storage.backend.pending")).contains("invalid-backend");
        assertThat(storagePaths.sqliteDatabaseFile()).doesNotExist();
        assertThat(storagePaths.h2DatabaseFile()).exists();
    }

    private void assertThatSqliteConversationWasMigrated(StorageBackend activeBackend, UUID conversationId) throws Exception {
        assertThat(activeBackend).isEqualTo(StorageBackend.SQLITE);
        assertThat(settingsRepo.get("chat.storage.backend.active")).contains("sqlite");
        assertThat(settingsRepo.get("chat.storage.backend.pending")).isEmpty();
        assertThat(storagePaths.sqliteDatabaseFile()).exists();

        DataSource sqliteDataSource = PersistenceDataSourceFactory.create(storagePaths, StorageBackend.SQLITE);
        var sqliteRepo = new ConversationRepository(
                sqliteDataSource,
                storagePaths.attachmentsDirectory(),
                SqlDialects.forBackend(StorageBackend.SQLITE)
        );
        assertThat(sqliteRepo.loadConversation(conversationId).map(ConversationRepository.LoadedConversation::conversation)).isPresent();
        assertThat(sqliteRepo.loadConversation(conversationId).orElseThrow().messages())
                .hasSize(1)
                .first()
                .extracting(record -> record.message().content())
                .isEqualTo("hello from h2");
    }

    private MigrationFixture createMigrationFixture(StorageBackend backend) throws Exception {
        DataSource dataSource = PersistenceDataSourceFactory.create(storagePaths, backend);
        new DatabaseBootstrap(storagePaths, dataSource, SqlDialects.forBackend(backend)).init();
        var repository = new ConversationRepository(
                dataSource,
                storagePaths.attachmentsDirectory(),
                SqlDialects.forBackend(backend)
        );
        UUID conversationId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID firstMessageId = UUID.fromString("ffffffff-0000-0000-0000-000000000001");
        UUID secondMessageId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Instant timestamp = Instant.parse("2024-01-02T03:04:05.678Z");
        Path attachmentDirectory = Files.createDirectories(storagePaths.attachmentsDirectory().resolve("migration-fixture"));
        Path sharedFile = attachmentDirectory.resolve("shared.txt");
        Path uniqueFile = attachmentDirectory.resolve("unique.txt");
        Files.writeString(sharedFile, "shared");
        Files.writeString(uniqueFile, "unique");
        var sharedRef = new AttachmentRef(
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                sharedFile.toRealPath().toString(),
                "shared.txt",
                "text/plain",
                Files.size(sharedFile),
                "shared-sha"
        );
        var uniqueRef = new AttachmentRef(
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                uniqueFile.toRealPath().toString(),
                "unique.txt",
                "text/plain",
                Files.size(uniqueFile),
                "unique-sha"
        );
        Message firstMessage = new Message(
                Role.USER,
                List.of(new TextPart("first"), new FilePart(sharedRef)),
                timestamp,
                null
        );
        Message secondMessage = new Message(
                Role.ASSISTANT,
                List.of(new FilePart(sharedRef), new FilePart(uniqueRef)),
                timestamp,
                null
        );
        repository.createConversation(new ConversationRepository.CreateConversationCommand(
                conversationId,
                "Migration fixture",
                "OpenAI",
                "gpt-4o",
                ReasoningLevel.OFF,
                false,
                null,
                false,
                null,
                new ConversationHistoryEntry(firstMessageId, 1, firstMessage)
        ));
        repository.appendMessage(
                conversationId,
                new ConversationHistoryEntry(secondMessageId, 2, secondMessage)
        );
        try (var connection = dataSource.getConnection();
             var attachmentStatement = connection.prepareStatement(
                     "UPDATE attachments SET size_bytes = NULL WHERE id = ?"
             );
             var conversationStatement = connection.prepareStatement(
                     """
                     UPDATE conversations
                     SET is_favorite = NULL, agent_mode_enabled = NULL, web_search_enabled = NULL
                     WHERE id = ?
                     """
             )) {
            SqlDialect dialect = SqlDialects.forBackend(backend);
            dialect.bindUuid(attachmentStatement, 1, uniqueRef.id());
            attachmentStatement.executeUpdate();
            dialect.bindUuid(conversationStatement, 1, conversationId);
            conversationStatement.executeUpdate();
        }
        return new MigrationFixture(
                conversationId,
                List.of(firstMessageId, secondMessageId),
                timestamp,
                sharedRef.id(),
                uniqueRef.id()
        );
    }

    private void assertMigrationFixture(
            DataSource dataSource,
            SqlDialect dialect,
            ConversationRepository repository,
            MigrationFixture fixture
    ) throws Exception {
        List<ConversationRepository.MessageRecord> records = repository.loadConversation(fixture.conversationId())
                .orElseThrow()
                .messages();
        assertThat(records).extracting(ConversationRepository.MessageRecord::id)
                .containsExactlyElementsOf(fixture.messageIds());
        assertThat(records).extracting(ConversationRepository.MessageRecord::ordinal)
                .containsExactly(1, 2);
        assertThat(records).extracting(record -> record.message().timestamp())
                .containsExactly(fixture.timestamp(), fixture.timestamp());
        assertThat(records.getFirst().message().parts()).filteredOn(FilePart.class::isInstance)
                .extracting(part -> ((FilePart) part).attachmentRef().id())
                .containsExactly(fixture.sharedAttachmentId());
        assertThat(records.getLast().message().parts()).filteredOn(FilePart.class::isInstance)
                .extracting(part -> ((FilePart) part).attachmentRef().id())
                .containsExactly(fixture.sharedAttachmentId(), fixture.uniqueAttachmentId());
        try (var connection = dataSource.getConnection();
             var attachmentStatement = connection.prepareStatement(
                     "SELECT size_bytes FROM attachments WHERE id = ?"
             );
             var conversationStatement = connection.prepareStatement(
                     """
                     SELECT is_favorite, agent_mode_enabled, web_search_enabled
                     FROM conversations
                     WHERE id = ?
                     """
             )) {
            dialect.bindUuid(attachmentStatement, 1, fixture.uniqueAttachmentId());
            try (var rows = attachmentStatement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                rows.getLong(1);
                assertThat(rows.wasNull()).isTrue();
            }
            dialect.bindUuid(conversationStatement, 1, fixture.conversationId());
            try (var rows = conversationStatement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertNullableBoolean(rows, "is_favorite");
                assertNullableBoolean(rows, "agent_mode_enabled");
                assertNullableBoolean(rows, "web_search_enabled");
            }
        }
    }

    private void assertNullableBoolean(ResultSet rows, String columnName) throws SQLException {
        rows.getBoolean(columnName);
        assertThat(rows.wasNull()).isTrue();
    }

    private UUID createH2Conversation() throws Exception {
        DataSource h2DataSource = PersistenceDataSourceFactory.create(storagePaths, StorageBackend.H2);
        new DatabaseBootstrap(storagePaths, h2DataSource, SqlDialects.forBackend(StorageBackend.H2)).init();
        var h2Repo = new ConversationRepository(
                h2DataSource,
                storagePaths.attachmentsDirectory(),
                SqlDialects.forBackend(StorageBackend.H2)
        );
        return createConversation(h2Repo, "Migrated", "hello from h2");
    }

    private UUID createConversation(ConversationRepository repository, String title, String content) throws Exception {
        UUID conversationId = UUID.randomUUID();
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user(content));
        repository.createConversation(new ConversationRepository.CreateConversationCommand(
                conversationId,
                title,
                "OpenAI",
                "gpt-4o",
                ReasoningLevel.OFF,
                false,
                null,
                false,
                null,
                entry
        ));
        return conversationId;
    }

    private record MigrationFixture(
            UUID conversationId,
            List<UUID> messageIds,
            Instant timestamp,
            UUID sharedAttachmentId,
            UUID uniqueAttachmentId
    ) {
    }

    private void cleanStorage() throws IOException {
        if (storagePaths == null) {
            return;
        }
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
