package com.github.drafael.chat4j.persistence.migration;

import com.github.drafael.chat4j.persistence.conversation.ConversationRepository;
import com.github.drafael.chat4j.persistence.db.DatabaseBootstrap;
import com.github.drafael.chat4j.persistence.db.PersistenceDataSourceFactory;
import com.github.drafael.chat4j.persistence.db.SqlDialects;
import com.github.drafael.chat4j.persistence.db.StorageBackend;
import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.api.Message;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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

        UUID conversationId = subject.createConversation("SQLite", "OpenAI", "gpt-4o");
        subject.addMessage(conversationId, Message.user("hello from sqlite"));

        assertThat(subject.findById(conversationId)).isPresent();
        assertThat(subject.getMessages(conversationId))
                .hasSize(1)
                .first()
                .extracting(record -> record.message().content())
                .isEqualTo("hello from sqlite");
    }

    @Test
    @DisplayName("Migration copies chat data from SQLite to H2 and marks H2 active")
    void migrateIfNeeded_whenPendingIsH2_copiesSqliteDataAndMarksH2Active() throws Exception {
        UUID conversationId = createSqliteConversation();
        settingsRepo.put("chat.storage.backend.active", StorageBackend.SQLITE.settingValue());
        settingsRepo.put("chat.storage.backend.pending", StorageBackend.H2.settingValue());
        var subject = new PersistenceBackendMigrationService(storagePaths, settingsRepo);

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
        assertThat(h2Repo.findById(conversationId)).isPresent();
        assertThat(h2Repo.getMessages(conversationId))
                .hasSize(1)
                .first()
                .extracting(record -> record.message().content())
                .isEqualTo("hello from sqlite");
    }

    @Test
    @DisplayName("Migration copies chat data from H2 to SQLite and marks SQLite active")
    void migrateIfNeeded_whenPendingIsSqlite_copiesH2DataAndMarksSqliteActive() throws Exception {
        UUID conversationId = createH2Conversation();
        settingsRepo.put("chat.storage.backend.active", StorageBackend.H2.settingValue());
        settingsRepo.put("chat.storage.backend.pending", StorageBackend.SQLITE.settingValue());
        var subject = new PersistenceBackendMigrationService(storagePaths, settingsRepo);

        StorageBackend activeBackend = subject.migrateIfNeeded();

        assertThatSqliteConversationWasMigrated(activeBackend, conversationId);
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
        assertThat(sqliteRepo.findById(conversationId)).isPresent();
        assertThat(sqliteRepo.getMessages(conversationId))
                .hasSize(1)
                .first()
                .extracting(record -> record.message().content())
                .isEqualTo("hello from h2");
    }

    private UUID createSqliteConversation() throws Exception {
        DataSource sqliteDataSource = PersistenceDataSourceFactory.create(storagePaths, StorageBackend.SQLITE);
        new DatabaseBootstrap(storagePaths, sqliteDataSource, SqlDialects.forBackend(StorageBackend.SQLITE)).init();
        var sqliteRepo = new ConversationRepository(
                sqliteDataSource,
                storagePaths.attachmentsDirectory(),
                SqlDialects.forBackend(StorageBackend.SQLITE)
        );
        UUID conversationId = sqliteRepo.createConversation("Migrated", "OpenAI", "gpt-4o");
        sqliteRepo.addMessage(conversationId, Message.user("hello from sqlite"));
        return conversationId;
    }

    private UUID createH2Conversation() throws Exception {
        DataSource h2DataSource = PersistenceDataSourceFactory.create(storagePaths, StorageBackend.H2);
        new DatabaseBootstrap(storagePaths, h2DataSource, SqlDialects.forBackend(StorageBackend.H2)).init();
        var h2Repo = new ConversationRepository(
                h2DataSource,
                storagePaths.attachmentsDirectory(),
                SqlDialects.forBackend(StorageBackend.H2)
        );
        UUID conversationId = h2Repo.createConversation("Migrated", "OpenAI", "gpt-4o");
        h2Repo.addMessage(conversationId, Message.user("hello from h2"));
        return conversationId;
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
