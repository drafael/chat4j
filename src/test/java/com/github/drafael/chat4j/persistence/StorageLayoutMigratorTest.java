package com.github.drafael.chat4j.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StorageLayoutMigratorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Legacy storage is moved into its scoped homes before consumers open it")
    void migrate_whenLegacyLayoutExists_movesKnownStorage() throws Exception {
        var paths = paths();
        Files.createDirectories(paths.legacyDatabaseDirectory());
        Files.writeString(paths.legacyDatabaseDirectory().resolve("chat4j.sqlite3"), "database");
        Files.createDirectories(paths.legacyAttachmentsDirectory());
        Files.writeString(paths.legacyAttachmentsDirectory().resolve("attachment.txt"), "attachment");
        Files.createDirectories(paths.legacyPromptsFile().getParent());
        Files.writeString(paths.legacyPromptsFile(), "prompts");
        Files.createDirectories(paths.legacySecretsDirectory());
        Files.writeString(paths.legacySecretsDirectory().resolve("token-vault.json"), "vault");
        Files.writeString(paths.legacyDatabaseCredentialsFile(), "credentials");
        Files.writeString(paths.legacyCopilotAuthFile(), "copilot");
        Files.writeString(paths.legacyCodexAuthFile(), "codex");
        Files.createDirectories(paths.legacySttModelsDirectory());
        Files.writeString(paths.legacySttModelsDirectory().resolve("model.bin"), "model");
        Files.createDirectories(paths.legacyCacheDirectory());
        Files.writeString(paths.legacyCacheDirectory().resolve("OpenAI.txt"), "cache");
        Files.createDirectories(paths.legacyJcefBundleDirectory());
        Files.writeString(paths.legacyJcefBundleDirectory().resolve("runtime.bin"), "runtime");
        Files.createDirectories(paths.legacySttTempDirectory());
        Files.writeString(paths.legacySttTempDirectory().resolve("recording.wav"), "recording");
        Files.createDirectories(paths.legacyLogsDirectory());
        Files.writeString(paths.legacyLogsDirectory().resolve("chat4j.log"), "log");
        var subject = new StorageLayoutMigrator(paths);

        subject.migrate();

        assertThat(paths.sqliteDatabaseFile()).hasContent("database");
        assertThat(paths.attachmentsDirectory().resolve("attachment.txt")).hasContent("attachment");
        assertThat(paths.promptsFile()).hasContent("prompts");
        assertThat(paths.tokenVaultFile()).hasContent("vault");
        assertThat(paths.databaseCredentialsFile()).hasContent("credentials");
        assertThat(paths.copilotAuthFile()).hasContent("copilot");
        assertThat(paths.codexAuthFile()).hasContent("codex");
        assertThat(paths.sttModelsDirectory().resolve("model.bin")).hasContent("model");
        assertThat(paths.cacheDirectory().resolve("OpenAI.txt")).hasContent("cache");
        assertThat(paths.jcefBundleDirectory().resolve("runtime.bin")).hasContent("runtime");
        assertThat(paths.sttTempDirectory().resolve("recording.wav")).hasContent("recording");
        assertThat(paths.logsDirectory().resolve("legacy-config/chat4j.log")).hasContent("log");
        assertThat(paths.legacyDatabaseDirectory()).doesNotExist();
        assertThat(paths.legacyAttachmentsDirectory()).doesNotExist();
    }

    @Test
    @DisplayName("Identical completed durable migration remnants are removed on retry")
    void migrate_whenDurableSourceAndDestinationAreIdentical_removesLegacyCopy() throws Exception {
        var paths = paths();
        Files.createDirectories(paths.legacyAttachmentsDirectory());
        Files.createDirectories(paths.attachmentsDirectory());
        Files.writeString(paths.legacyAttachmentsDirectory().resolve("same.txt"), "same");
        Files.writeString(paths.attachmentsDirectory().resolve("same.txt"), "same");
        var subject = new StorageLayoutMigrator(paths);

        subject.migrate();

        assertThat(paths.legacyAttachmentsDirectory()).doesNotExist();
        assertThat(paths.attachmentsDirectory().resolve("same.txt")).hasContent("same");
    }

    @Test
    @DisplayName("Conflicting durable storage stops migration without replacing either copy")
    void migrate_whenDurableSourceAndDestinationConflict_throwsException() throws Exception {
        var paths = paths();
        Files.createDirectories(paths.legacyPromptsFile().getParent());
        Files.createDirectories(paths.promptsFile().getParent());
        Files.writeString(paths.legacyPromptsFile(), "legacy");
        Files.writeString(paths.promptsFile(), "current");
        var subject = new StorageLayoutMigrator(paths);

        assertThatThrownBy(subject::migrate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prompt catalog");
        assertThat(paths.legacyPromptsFile()).hasContent("legacy");
        assertThat(paths.promptsFile()).hasContent("current");
    }

    @Test
    @DisplayName("Durable migration rejects symbolic links nested below the legacy root")
    void migrate_whenDurableSourceContainsSymbolicLink_stopsWithoutMovingIt() throws Exception {
        var paths = paths();
        Path source = Files.createDirectories(paths.legacyAttachmentsDirectory());
        Path external = Files.writeString(tempDir.resolve("external.txt"), "outside");
        try {
            Files.createSymbolicLink(source.resolve("linked.txt"), external);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "Symbolic links are unavailable on this filesystem");
        }
        var subject = new StorageLayoutMigrator(paths);

        assertThatThrownBy(subject::migrate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("attachments");
        assertThat(source.resolve("linked.txt")).isSymbolicLink();
        assertThat(paths.attachmentsDirectory()).doesNotExist();
        assertThat(external).hasContent("outside");
    }

    @Test
    @DisplayName("Current cache wins over conflicting replaceable legacy content")
    void migrate_whenCacheSourceAndDestinationConflict_keepsCurrentCache() throws Exception {
        var paths = paths();
        Files.createDirectories(paths.legacyCacheDirectory());
        Files.createDirectories(paths.cacheDirectory());
        Files.writeString(paths.legacyCacheDirectory().resolve("models.txt"), "legacy");
        Files.writeString(paths.cacheDirectory().resolve("models.txt"), "current");
        var subject = new StorageLayoutMigrator(paths);

        subject.migrate();

        assertThat(paths.legacyCacheDirectory()).doesNotExist();
        assertThat(paths.cacheDirectory().resolve("models.txt")).hasContent("current");
    }

    private StoragePaths paths() {
        return StoragePaths.ofBaseHomes(
                tempDir.resolve("config"),
                tempDir.resolve("data"),
                tempDir.resolve("cache"),
                tempDir.resolve("state")
        );
    }
}
