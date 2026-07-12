package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.persistence.StoragePaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiTokenVaultTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Vault creates master key lazily on first save and round-trips encrypted tokens")
    void saveToken_whenFirstTokenSaved_createsMasterKeyAndRoundTripsWithoutPlaintext() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var subject = new ApiTokenVault(storagePaths);

        assertThat(Files.exists(storagePaths.tokenVaultMasterKeyFile())).isFalse();

        subject.saveToken("OPENAI_API_KEY", "secret-token".toCharArray());

        assertThat(Files.exists(storagePaths.tokenVaultMasterKeyFile())).isTrue();
        assertThat(Files.readString(storagePaths.tokenVaultFile())).doesNotContain("secret-token");
        try (ApiTokenLookup lookup = subject.readTokenChars("OPENAI_API_KEY")) {
            assertThat(lookup.present()).isTrue();
            assertThat(String.valueOf(lookup.token())).isEqualTo("secret-token");
        }
    }

    @Test
    @DisplayName("Read-only startup checks do not create secret files")
    void refreshFromDiskReadOnly_whenVaultMissing_doesNotCreateSecretFiles() {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var subject = new ApiTokenVault(storagePaths);

        subject.refreshFromDiskReadOnly();

        assertThat(Files.exists(storagePaths.secretsDirectory())).isFalse();
        assertThat(subject.hasReadableToken("OPENAI_API_KEY")).isFalse();
    }

    @Test
    @DisplayName("Delete removes token records without decrypting them")
    void deleteToken_whenRecordExists_removesRecord() {
        var subject = new ApiTokenVault(StoragePaths.ofConfigHome(tempDir));
        subject.saveToken("OPENAI_API_KEY", "secret-token".toCharArray());

        subject.deleteToken("OPENAI_API_KEY");

        assertThat(subject.hasRecord("OPENAI_API_KEY")).isFalse();
        assertThat(subject.hasReadableToken("OPENAI_API_KEY")).isFalse();
    }

    @Test
    @DisplayName("Vault rejects unsupported token ids")
    void saveToken_whenTokenIdUnsupported_throwsException() {
        var subject = new ApiTokenVault(StoragePaths.ofConfigHome(tempDir));

        assertThatThrownBy(() -> subject.saveToken("../OPENAI_API_KEY", "secret".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AAD catches token-id swaps")
    void readTokenChars_whenRecordIsMovedToDifferentTokenId_reportsError() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var subject = new ApiTokenVault(storagePaths);
        subject.saveToken("OPENAI_API_KEY", "secret-token".toCharArray());
        String json = Files.readString(storagePaths.tokenVaultFile())
                .replace("OPENAI_API_KEY", "GROQ_API_KEY");
        Files.writeString(storagePaths.tokenVaultFile(), json);
        subject.refreshFromDiskReadOnly();

        try (ApiTokenLookup lookup = subject.readTokenChars("GROQ_API_KEY")) {
            assertThat(lookup.source()).isEqualTo(ApiCredentialSource.ERROR);
        }
        assertThat(subject.status("GROQ_API_KEY").source()).isEqualTo(ApiCredentialSource.ERROR);
    }

    @Test
    @DisplayName("Recreate backs up corrupt vault and allows a new save")
    void recreateVault_whenVaultJsonIsCorrupt_allowsNewSave() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        Files.createDirectories(storagePaths.secretsDirectory());
        Files.writeString(storagePaths.tokenVaultFile(), "not-json");
        var subject = new ApiTokenVault(storagePaths);

        try (ApiTokenLookup lookup = subject.readTokenChars("OPENAI_API_KEY")) {
            assertThat(lookup.source()).isEqualTo(ApiCredentialSource.ERROR);
            assertThat(lookup.errorMessage())
                    .isEqualTo("Could not read saved token vault.")
                    .doesNotContain("not-json");
        }
        assertThat(subject.status("OPENAI_API_KEY"))
                .extracting(ApiCredentialStatus::source, ApiCredentialStatus::errorMessage)
                .containsExactly(ApiCredentialSource.ERROR, "Could not read saved token vault.");

        subject.recreateVault();
        subject.saveToken("OPENAI_API_KEY", "new-secret".toCharArray());

        try (ApiTokenLookup lookup = subject.readTokenChars("OPENAI_API_KEY")) {
            assertThat(lookup.present()).isTrue();
            assertThat(String.valueOf(lookup.token())).isEqualTo("new-secret");
        }
        try (var files = Files.list(storagePaths.secretsDirectory())) {
            assertThat(files
                    .map(path -> path.getFileName().toString())
                    .anyMatch(name -> name.startsWith("token-vault.json.corrupt-")))
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Delete errors redact corrupt vault file contents")
    void deleteToken_whenVaultJsonIsCorrupt_throwsRedactedUpdateError() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        Files.createDirectories(storagePaths.secretsDirectory());
        Files.writeString(storagePaths.tokenVaultFile(), "SUPER_SECRET_VAULT_CONTENT");
        var subject = new ApiTokenVault(storagePaths);

        assertThatThrownBy(() -> subject.deleteToken("OPENAI_API_KEY"))
                .isInstanceOf(ApiTokenVault.ApiTokenVaultException.class)
                .hasMessage("Could not update token vault.")
                .hasMessageNotContaining("SUPER_SECRET_VAULT_CONTENT");
    }

    @Test
    @DisplayName("Existing saved tokens fail closed when the master key is missing until vault recreation")
    void saveToken_whenExistingRecordMasterKeyMissing_failsUntilRecreated() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var subject = new ApiTokenVault(storagePaths);
        subject.saveToken("OPENAI_API_KEY", "old-secret".toCharArray());
        Files.delete(storagePaths.tokenVaultMasterKeyFile());

        subject = new ApiTokenVault(storagePaths);

        assertThat(subject.status("OPENAI_API_KEY"))
                .extracting(ApiCredentialStatus::source, ApiCredentialStatus::errorMessage)
                .containsExactly(ApiCredentialSource.ERROR, "Saved token vault key is unavailable.");
        try (ApiTokenLookup lookup = subject.readTokenChars("OPENAI_API_KEY")) {
            assertThat(lookup.source()).isEqualTo(ApiCredentialSource.ERROR);
            assertThat(lookup.errorMessage()).isEqualTo("Saved token vault key is unavailable.");
        }
        var failingVault = subject;
        assertThatThrownBy(() -> failingVault.saveToken("OPENAI_API_KEY", "new-secret".toCharArray()))
                .isInstanceOf(ApiTokenVault.ApiTokenVaultException.class)
                .hasMessageContaining("Saved token vault key is unavailable.");

        subject.recreateVault();
        subject.saveToken("OPENAI_API_KEY", "new-secret".toCharArray());

        try (ApiTokenLookup lookup = subject.readTokenChars("OPENAI_API_KEY")) {
            assertThat(lookup.present()).isTrue();
            assertThat(String.valueOf(lookup.token())).isEqualTo("new-secret");
        }
    }

    @Test
    @DisplayName("Existing saved tokens fail closed when the master key is corrupt until vault recreation")
    void saveToken_whenExistingRecordMasterKeyCorrupt_failsUntilRecreated() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var subject = new ApiTokenVault(storagePaths);
        subject.saveToken("OPENAI_API_KEY", "old-secret".toCharArray());
        Files.writeString(storagePaths.tokenVaultMasterKeyFile(), "not-base64");

        subject = new ApiTokenVault(storagePaths);

        assertThat(subject.status("OPENAI_API_KEY"))
                .extracting(ApiCredentialStatus::source, ApiCredentialStatus::errorMessage)
                .containsExactly(ApiCredentialSource.ERROR, "Saved token vault key is unavailable.");
        try (ApiTokenLookup lookup = subject.readTokenChars("OPENAI_API_KEY")) {
            assertThat(lookup.source()).isEqualTo(ApiCredentialSource.ERROR);
            assertThat(lookup.errorMessage()).isEqualTo("Saved token vault key is unavailable.");
        }
        var failingVault = subject;
        assertThatThrownBy(() -> failingVault.saveToken("OPENAI_API_KEY", "new-secret".toCharArray()))
                .isInstanceOf(ApiTokenVault.ApiTokenVaultException.class)
                .hasMessageContaining("Saved token vault key is unavailable.");

        subject.recreateVault();
        subject.saveToken("OPENAI_API_KEY", "new-secret".toCharArray());

        try (ApiTokenLookup lookup = subject.readTokenChars("OPENAI_API_KEY")) {
            assertThat(lookup.present()).isTrue();
            assertThat(String.valueOf(lookup.token())).isEqualTo("new-secret");
        }
    }

    @Test
    @DisplayName("Vault waits for overlapping same-JVM file locks instead of failing immediately")
    void saveToken_whenSameJvmLockOverlaps_waitsAndThenSaves() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        Files.createDirectories(storagePaths.secretsDirectory());
        var subject = new ApiTokenVault(storagePaths);

        try (FileChannel channel = FileChannel.open(
                storagePaths.tokenVaultLockFile(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        ); FileLock lock = channel.lock()) {
            CompletableFuture<Void> save = CompletableFuture.runAsync(() ->
                    subject.saveToken("OPENAI_API_KEY", "secret-token".toCharArray()));
            Thread.sleep(100);
            assertThat(save).isNotDone();
            lock.release();
            save.get(2, TimeUnit.SECONDS);
        }

        try (ApiTokenLookup lookup = subject.readTokenChars("OPENAI_API_KEY")) {
            assertThat(lookup.present()).isTrue();
        }
    }

    @Test
    @DisplayName("Lookup masks token-bearing toString output")
    void toString_whenLookupContainsToken_masksToken() {
        try (ApiTokenLookup lookup = ApiTokenLookup.present("OPENAI_API_KEY", "secret-token".toCharArray())) {
            assertThat(lookup.toString()).doesNotContain("secret-token").contains("<masked>");
        }
    }
}
