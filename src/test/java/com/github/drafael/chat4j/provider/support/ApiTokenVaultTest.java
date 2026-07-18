package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.persistence.StoragePaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Arrays.fill;
import static java.util.stream.Collectors.joining;
import static java.util.stream.IntStream.range;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ApiTokenVaultTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Vault creates master key lazily on first save and round-trips encrypted tokens")
    void applyTokenMutation_whenFirstTokenSaved_createsMasterKeyAndRoundTripsWithoutPlaintext() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var subject = new ApiTokenVault(storagePaths);

        assertThat(Files.exists(storagePaths.tokenVaultMasterKeyFile())).isFalse();

        CredentialTestSupport.saveToken(subject, "OPENAI_API_KEY", "secret-token".toCharArray());

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
        assertThat(subject.status("OPENAI_API_KEY").source()).isEqualTo(ApiCredentialSource.MISSING);
    }

    @Test
    @DisplayName("Delete removes token records without decrypting them")
    void applyTokenMutation_whenRecordExists_removesRecord() {
        var subject = new ApiTokenVault(StoragePaths.ofConfigHome(tempDir));
        CredentialTestSupport.saveToken(subject, "OPENAI_API_KEY", "secret-token".toCharArray());

        CredentialTestSupport.deleteToken(subject, "OPENAI_API_KEY");

        assertThat(subject.hasRecord("OPENAI_API_KEY")).isFalse();
        try (ApiTokenLookup lookup = subject.readTokenChars("OPENAI_API_KEY")) {
            assertThat(lookup.present()).isFalse();
        }
    }

    @Test
    @DisplayName("Vault rejects unsupported token ids")
    void applyTokenMutation_whenTokenIdUnsupported_throwsException() {
        var subject = new ApiTokenVault(StoragePaths.ofConfigHome(tempDir));

        assertThatThrownBy(() -> CredentialTestSupport.saveToken(subject, "../OPENAI_API_KEY", "secret".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AAD catches token-id swaps")
    void readTokenChars_whenRecordIsMovedToDifferentTokenId_reportsError() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var subject = new ApiTokenVault(storagePaths);
        CredentialTestSupport.saveToken(subject, "OPENAI_API_KEY", "secret-token".toCharArray());
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
        CredentialTestSupport.saveToken(subject, "OPENAI_API_KEY", "new-secret".toCharArray());

        try (ApiTokenLookup lookup = subject.readTokenChars("OPENAI_API_KEY")) {
            assertThat(lookup.present()).isTrue();
            assertThat(String.valueOf(lookup.token())).isEqualTo("new-secret");
        }
        try (var files = Files.list(storagePaths.secretsDirectory())) {
            assertThat(files
                    .map(path -> path.getFileName().toString())
                    .anyMatch(name -> name.startsWith("token-vault.json.backup-")))
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Delete errors redact corrupt vault file contents")
    void applyTokenMutation_whenVaultJsonIsCorrupt_throwsRedactedUpdateError() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        Files.createDirectories(storagePaths.secretsDirectory());
        Files.writeString(storagePaths.tokenVaultFile(), "SUPER_SECRET_VAULT_CONTENT");
        var subject = new ApiTokenVault(storagePaths);

        assertThatThrownBy(() -> CredentialTestSupport.deleteToken(subject, "OPENAI_API_KEY"))
                .isInstanceOf(ApiTokenVault.ApiTokenVaultException.class)
                .hasMessage("Could not read saved token vault.")
                .hasMessageNotContaining("SUPER_SECRET_VAULT_CONTENT");
    }

    @Test
    @DisplayName("Existing saved tokens fail closed when the master key is missing until vault recreation")
    void applyTokenMutation_whenExistingRecordMasterKeyMissing_failsUntilRecreated() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var subject = new ApiTokenVault(storagePaths);
        CredentialTestSupport.saveToken(subject, "OPENAI_API_KEY", "old-secret".toCharArray());
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
        assertThatThrownBy(() -> CredentialTestSupport.saveToken(
                failingVault,
                "OPENAI_API_KEY",
                "new-secret".toCharArray()
        ))
                .isInstanceOf(ApiTokenVault.ApiTokenVaultException.class)
                .hasMessageContaining("Saved token vault key is unavailable.");

        subject.recreateVault();
        CredentialTestSupport.saveToken(subject, "OPENAI_API_KEY", "new-secret".toCharArray());

        try (ApiTokenLookup lookup = subject.readTokenChars("OPENAI_API_KEY")) {
            assertThat(lookup.present()).isTrue();
            assertThat(String.valueOf(lookup.token())).isEqualTo("new-secret");
        }
    }

    @Test
    @DisplayName("Existing saved tokens fail closed when the master key is corrupt until vault recreation")
    void applyTokenMutation_whenExistingRecordMasterKeyCorrupt_failsUntilRecreated() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var subject = new ApiTokenVault(storagePaths);
        CredentialTestSupport.saveToken(subject, "OPENAI_API_KEY", "old-secret".toCharArray());
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
        assertThatThrownBy(() -> CredentialTestSupport.saveToken(
                failingVault,
                "OPENAI_API_KEY",
                "new-secret".toCharArray()
        ))
                .isInstanceOf(ApiTokenVault.ApiTokenVaultException.class)
                .hasMessageContaining("Saved token vault key is unavailable.");

        subject.recreateVault();
        CredentialTestSupport.saveToken(subject, "OPENAI_API_KEY", "new-secret".toCharArray());

        try (ApiTokenLookup lookup = subject.readTokenChars("OPENAI_API_KEY")) {
            assertThat(lookup.present()).isTrue();
            assertThat(String.valueOf(lookup.token())).isEqualTo("new-secret");
        }
    }

    @Test
    @DisplayName("Vault waits for overlapping same-JVM file locks instead of failing immediately")
    void applyTokenMutation_whenSameJvmLockOverlaps_waitsAndThenSaves() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        Files.createDirectories(storagePaths.secretsDirectory());
        var subject = new ApiTokenVault(storagePaths);

        try (FileChannel channel = FileChannel.open(
                storagePaths.tokenVaultLockFile(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        ); FileLock lock = channel.lock()) {
            CompletableFuture<Void> save = CompletableFuture.runAsync(() ->
                    CredentialTestSupport.saveToken(subject, "OPENAI_API_KEY", "secret-token".toCharArray()));
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
    @DisplayName("Vault files over four MiB fail closed without being parsed")
    void status_whenVaultExceedsSizeLimit_reportsReadError() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        Files.createDirectories(storagePaths.secretsDirectory());
        Files.write(storagePaths.tokenVaultFile(), new byte[ApiTokenVault.MAX_VAULT_BYTES + 1]);

        var subject = new ApiTokenVault(storagePaths);

        assertThat(subject.status("OPENAI_API_KEY"))
                .extracting(ApiCredentialStatus::source, ApiCredentialStatus::errorMessage)
                .containsExactly(ApiCredentialSource.ERROR, "Could not read saved token vault.");
    }

    @Test
    @DisplayName("Master key files over one KiB fail closed before decoding")
    void status_whenMasterKeyExceedsSizeLimit_reportsKeyError() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var subject = new ApiTokenVault(storagePaths);
        subject.recreateVault();
        Files.write(storagePaths.tokenVaultMasterKeyFile(), new byte[ApiTokenVault.MAX_KEY_FILE_BYTES + 1]);

        subject = new ApiTokenVault(storagePaths);

        assertThat(subject.status("OPENAI_API_KEY"))
                .extracting(ApiCredentialStatus::source, ApiCredentialStatus::errorMessage)
                .containsExactly(ApiCredentialSource.ERROR, "Saved token vault key is unavailable.");
    }

    @Test
    @DisplayName("Duplicate JSON properties are rejected before vault records are accepted")
    void status_whenVaultContainsDuplicateProperties_reportsReadError() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var subject = new ApiTokenVault(storagePaths);
        CredentialTestSupport.saveToken(subject, "OPENAI_API_KEY", "secret".toCharArray());
        String json = Files.readString(storagePaths.tokenVaultFile())
                .replaceFirst("\\\"records\\\"", "\\\"records\\\" : {}, \\\"records\\\"");
        Files.writeString(storagePaths.tokenVaultFile(), json);

        subject = new ApiTokenVault(storagePaths);

        assertThat(subject.status("OPENAI_API_KEY").source()).isEqualTo(ApiCredentialSource.ERROR);
    }

    @Test
    @DisplayName("Structurally invalid nonces are rejected before decryption")
    void status_whenNonceLengthIsInvalid_reportsReadError() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var subject = new ApiTokenVault(storagePaths);
        CredentialTestSupport.saveToken(subject, "OPENAI_API_KEY", "secret".toCharArray());
        String json = Files.readString(storagePaths.tokenVaultFile())
                .replaceFirst("(\\\"nonce\\\"\\s*:\\s*)\\\"[^\\\"]+\\\"", "$1\\\"AA==\\\"");
        Files.writeString(storagePaths.tokenVaultFile(), json);

        subject = new ApiTokenVault(storagePaths);

        assertThat(subject.status("OPENAI_API_KEY").source()).isEqualTo(ApiCredentialSource.ERROR);
    }

    @Test
    @DisplayName("A symbolic-link secrets directory fails closed without reading the linked vault")
    void status_whenSecretsDirectoryIsSymbolicLink_reportsReadError() throws Exception {
        var externalPaths = StoragePaths.ofConfigHome(tempDir.resolve("external"));
        var externalVault = new ApiTokenVault(externalPaths);
        CredentialTestSupport.saveToken(externalVault, "OPENAI_API_KEY", "external-secret".toCharArray());
        var storagePaths = StoragePaths.ofConfigHome(tempDir.resolve("actual"));
        Files.createDirectories(storagePaths.secretsDirectory().getParent());
        try {
            Files.createSymbolicLink(storagePaths.secretsDirectory(), externalPaths.secretsDirectory());
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "Symbolic links are not supported");
        }

        var subject = new ApiTokenVault(storagePaths);

        assertThat(subject.status("OPENAI_API_KEY"))
                .extracting(ApiCredentialStatus::source, ApiCredentialStatus::errorMessage)
                .containsExactly(ApiCredentialSource.ERROR, "Could not read saved token vault.");
    }

    @Test
    @DisplayName("Replacing the secrets directory makes read-only refresh fail closed")
    void refreshFromDiskReadOnly_whenSecretsDirectoryIdentityChanges_reportsReadError() throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        var storagePaths = StoragePaths.ofConfigHome(tempDir.resolve("actual"));
        var subject = new ApiTokenVault(storagePaths);
        CredentialTestSupport.saveToken(subject, "OPENAI_API_KEY", "original-secret".toCharArray());
        var externalPaths = StoragePaths.ofConfigHome(tempDir.resolve("external"));
        var externalVault = new ApiTokenVault(externalPaths);
        CredentialTestSupport.saveToken(externalVault, "OPENAI_API_KEY", "external-secret".toCharArray());
        Files.move(storagePaths.secretsDirectory(), storagePaths.secretsDirectory().resolveSibling("original-secrets"));
        try {
            Files.createSymbolicLink(storagePaths.secretsDirectory(), externalPaths.secretsDirectory());
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "Symbolic links are not supported");
        }

        subject.refreshFromDiskReadOnly();

        assertThat(subject.status("OPENAI_API_KEY"))
                .extracting(ApiCredentialStatus::source, ApiCredentialStatus::errorMessage)
                .containsExactly(ApiCredentialSource.ERROR, "Could not read saved token vault.");
    }

    @Test
    @DisplayName("Symbolic-link vault targets are rejected without following their content")
    void status_whenVaultTargetIsSymbolicLink_reportsReadError() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        Files.createDirectories(storagePaths.secretsDirectory());
        Path external = tempDir.resolve("external-vault.json");
        Files.writeString(external, "sensitive-external-content");
        try {
            Files.createSymbolicLink(storagePaths.tokenVaultFile(), external);
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "Symbolic links are not supported");
        }

        var subject = new ApiTokenVault(storagePaths);

        assertThat(subject.status("OPENAI_API_KEY").source()).isEqualTo(ApiCredentialSource.ERROR);
        assertThat(Files.readString(external)).isEqualTo("sensitive-external-content");
    }

    @Test
    @DisplayName("Vault recreation preserves malformed files in non-clobbering bounded backups")
    void recreateVault_whenBackupNameAlreadyExists_preservesEveryFileWithoutClobbering() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        Files.createDirectories(storagePaths.secretsDirectory());
        Files.writeString(storagePaths.tokenVaultFile(), "malformed-vault");
        Files.writeString(storagePaths.tokenVaultMasterKeyFile(), "malformed-key");
        Path existingBackup = storagePaths.tokenVaultFile().resolveSibling("token-vault.json.backup-0000");
        Files.writeString(existingBackup, "older-backup");
        var subject = new ApiTokenVault(storagePaths);

        subject.recreateVault();

        assertThat(Files.readString(existingBackup)).isEqualTo("older-backup");
        assertThat(Files.readString(storagePaths.tokenVaultFile().resolveSibling("token-vault.json.backup-0001")))
                .isEqualTo("malformed-vault");
        assertThat(Files.readString(storagePaths.tokenVaultMasterKeyFile().resolveSibling("master.key.backup-0000")))
                .isEqualTo("malformed-key");
        assertThat(subject.status("OPENAI_API_KEY").source()).isEqualTo(ApiCredentialSource.MISSING);
        try (var files = Files.list(storagePaths.secretsDirectory())) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.endsWith(".tmp"));
        }
    }

    @Test
    @DisplayName("Missing records property is rejected as malformed schema")
    void status_whenRecordsPropertyIsMissing_reportsReadError() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        Files.createDirectories(storagePaths.secretsDirectory());
        Files.writeString(storagePaths.tokenVaultFile(), "{\"schemaVersion\":1}");

        var subject = new ApiTokenVault(storagePaths);

        assertThat(subject.status("OPENAI_API_KEY").source()).isEqualTo(ApiCredentialSource.ERROR);
    }

    @Test
    @DisplayName("Null records property is rejected as malformed schema")
    void status_whenRecordsPropertyIsNull_reportsReadError() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        Files.createDirectories(storagePaths.secretsDirectory());
        Files.writeString(storagePaths.tokenVaultFile(), "{\"schemaVersion\":1,\"records\":null}");

        var subject = new ApiTokenVault(storagePaths);

        assertThat(subject.status("OPENAI_API_KEY").source()).isEqualTo(ApiCredentialSource.ERROR);
    }

    @Test
    @DisplayName("Nonce collisions are regenerated before a second record is encrypted")
    void applyTokenMutation_whenRandomNonceCollides_regeneratesUniqueNonce() throws Exception {
        var nonceCalls = new AtomicInteger();
        var random = new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                if (bytes.length == 12) {
                    fill(bytes, nonceCalls.getAndIncrement() < 2 ? (byte) 7 : (byte) 8);
                    return;
                }
                super.nextBytes(bytes);
            }
        };
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var subject = new ApiTokenVault(storagePaths, random);

        CredentialTestSupport.saveToken(subject, "OPENAI_API_KEY", "openai".toCharArray());
        CredentialTestSupport.saveToken(subject, "GROQ_API_KEY", "groq".toCharArray());

        var matcher = Pattern.compile("\\\"nonce\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .matcher(Files.readString(storagePaths.tokenVaultFile()));
        assertThat(matcher.results().map(result -> result.group(1)).distinct()).hasSize(2);
        assertThat(nonceCalls).hasValue(3);
    }

    @Test
    @DisplayName("Vaults with duplicate nonces across records fail closed")
    void status_whenTwoRecordsReuseNonce_reportsReadError() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var subject = new ApiTokenVault(storagePaths);
        CredentialTestSupport.saveToken(subject, "OPENAI_API_KEY", "openai".toCharArray());
        CredentialTestSupport.saveToken(subject, "GROQ_API_KEY", "groq".toCharArray());
        String json = Files.readString(storagePaths.tokenVaultFile());
        var nonceMatches = Pattern.compile("\\\"nonce\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .matcher(json)
                .results()
                .toList();
        String duplicatedNonceJson = "%s%s%s".formatted(
                json.substring(0, nonceMatches.get(1).start(1)),
                nonceMatches.getFirst().group(1),
                json.substring(nonceMatches.get(1).end(1))
        );
        Files.writeString(storagePaths.tokenVaultFile(), duplicatedNonceJson);

        subject = new ApiTokenVault(storagePaths);

        assertThat(subject.status("OPENAI_API_KEY").source()).isEqualTo(ApiCredentialSource.ERROR);
    }

    @Test
    @DisplayName("Null record timestamps are rejected instead of normalized")
    void status_whenUpdatedAtIsNull_reportsReadError() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var subject = new ApiTokenVault(storagePaths);
        CredentialTestSupport.saveToken(subject, "OPENAI_API_KEY", "secret".toCharArray());
        String json = Files.readString(storagePaths.tokenVaultFile())
                .replaceFirst("(\\\"updatedAt\\\"\\s*:\\s*)\\\"[^\\\"]+\\\"", "$1null");
        Files.writeString(storagePaths.tokenVaultFile(), json);

        subject = new ApiTokenVault(storagePaths);

        assertThat(subject.status("OPENAI_API_KEY").source()).isEqualTo(ApiCredentialSource.ERROR);
    }

    @Test
    @DisplayName("Missing record timestamps are rejected as malformed schema")
    void status_whenUpdatedAtIsMissing_reportsReadError() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var subject = new ApiTokenVault(storagePaths);
        CredentialTestSupport.saveToken(subject, "OPENAI_API_KEY", "secret".toCharArray());
        String json = Files.readString(storagePaths.tokenVaultFile())
                .replaceFirst(",\\s*\\\"updatedAt\\\"\\s*:\\s*\\\"[^\\\"]+\\\"", "");
        Files.writeString(storagePaths.tokenVaultFile(), json);

        subject = new ApiTokenVault(storagePaths);

        assertThat(subject.status("OPENAI_API_KEY").source()).isEqualTo(ApiCredentialSource.ERROR);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidVaultStructures")
    @DisplayName("Malformed vault structures fail closed")
    void status_whenVaultStructureIsInvalid_reportsReadError(String scenario, String json) throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        Files.createDirectories(storagePaths.secretsDirectory());
        Files.writeString(storagePaths.tokenVaultFile(), json);

        var subject = new ApiTokenVault(storagePaths);

        assertThat(subject.status("OPENAI_API_KEY"))
                .extracting(ApiCredentialStatus::source, ApiCredentialStatus::errorMessage)
                .containsExactly(ApiCredentialSource.ERROR, "Could not read saved token vault.");
    }

    private static Stream<Arguments> invalidVaultStructures() {
        String validNonce = Base64.getEncoder().encodeToString(new byte[12]);
        String minimumCiphertext = Base64.getEncoder().encodeToString(new byte[16]);
        String oversizedCiphertext = Base64.getEncoder()
                .encodeToString(new byte[ApiTokenVault.MAX_TOKEN_BYTES + 17]);
        String validRecord = recordJson("AES-256-GCM", validNonce, minimumCiphertext);
        String tooManyRecords = range(0, 65)
                .mapToObj(index -> "\"UNKNOWN_%d\":%s".formatted(index, validRecord))
                .collect(joining(","));
        return Stream.of(
                Arguments.of("unsupported schema version", "{\"schemaVersion\":2,\"records\":{}}"),
                Arguments.of(
                        "unknown top-level property",
                        "{\"schemaVersion\":1,\"records\":{},\"unexpected\":true}"
                ),
                Arguments.of(
                        "unknown token id",
                        "{\"schemaVersion\":1,\"records\":{\"UNKNOWN_API_KEY\":%s}}".formatted(validRecord)
                ),
                Arguments.of(
                        "wrong algorithm",
                        "{\"schemaVersion\":1,\"records\":{\"OPENAI_API_KEY\":%s}}"
                                .formatted(recordJson("AES-CBC", validNonce, minimumCiphertext))
                ),
                Arguments.of(
                        "ciphertext shorter than GCM tag",
                        "{\"schemaVersion\":1,\"records\":{\"OPENAI_API_KEY\":%s}}"
                                .formatted(recordJson("AES-256-GCM", validNonce, "AA=="))
                ),
                Arguments.of(
                        "ciphertext exceeds token bound",
                        "{\"schemaVersion\":1,\"records\":{\"OPENAI_API_KEY\":%s}}"
                                .formatted(recordJson("AES-256-GCM", validNonce, oversizedCiphertext))
                ),
                Arguments.of(
                        "record count exceeds limit",
                        "{\"schemaVersion\":1,\"records\":{%s}}".formatted(tooManyRecords)
                )
        );
    }

    private static String recordJson(String algorithm, String nonce, String ciphertext) {
        return """
                {
                  "algorithm":"%s",
                  "nonce":"%s",
                  "ciphertext":"%s",
                  "updatedAt":"1970-01-01T00:00:00Z"
                }
                """.formatted(algorithm, nonce, ciphertext).strip();
    }

    @Test
    @DisplayName("Symbolic-link master key targets fail closed without modifying the link target")
    void status_whenMasterKeyIsSymbolicLink_reportsKeyError() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var subject = new ApiTokenVault(storagePaths);
        CredentialTestSupport.saveToken(subject, "OPENAI_API_KEY", "secret".toCharArray());
        Path external = tempDir.resolve("external-key");
        Files.writeString(external, "external-key-content");
        Files.delete(storagePaths.tokenVaultMasterKeyFile());
        try {
            Files.createSymbolicLink(storagePaths.tokenVaultMasterKeyFile(), external);
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "Symbolic links are not supported");
        }

        subject = new ApiTokenVault(storagePaths);

        assertThat(subject.status("OPENAI_API_KEY").source()).isEqualTo(ApiCredentialSource.ERROR);
        assertThat(Files.readString(external)).isEqualTo("external-key-content");
    }

    @Test
    @DisplayName("Symbolic-link lock paths reject mutations without modifying the link target")
    void applyTokenMutation_whenLockPathIsSymbolicLink_rejectsWithoutFollowingLink() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var subject = new ApiTokenVault(storagePaths);
        Files.createDirectories(storagePaths.secretsDirectory());
        Path external = tempDir.resolve("external-lock");
        Files.writeString(external, "external-lock-content");
        try {
            Files.createSymbolicLink(storagePaths.tokenVaultLockFile(), external);
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "Symbolic links are not supported");
        }

        assertThatThrownBy(() -> CredentialTestSupport.saveToken(subject, "OPENAI_API_KEY", "secret".toCharArray()))
                .isInstanceOf(ApiTokenVault.ApiTokenVaultException.class)
                .hasMessage("Could not update token vault.");
        assertThat(Files.readString(external)).isEqualTo("external-lock-content");
    }

    @Test
    @DisplayName("Unsafe backup candidates abort recreation without replacing original files")
    void recreateVault_whenBackupCandidateIsSymbolicLink_preservesOriginalFinals() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        Files.createDirectories(storagePaths.secretsDirectory());
        Files.writeString(storagePaths.tokenVaultFile(), "malformed-vault");
        Files.writeString(storagePaths.tokenVaultMasterKeyFile(), "malformed-key");
        Path external = tempDir.resolve("external-backup");
        Files.writeString(external, "external-backup-content");
        try {
            Files.createSymbolicLink(
                    storagePaths.tokenVaultFile().resolveSibling("token-vault.json.backup-0000"),
                    external
            );
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "Symbolic links are not supported");
        }
        var subject = new ApiTokenVault(storagePaths);

        assertThatThrownBy(subject::recreateVault)
                .isInstanceOf(ApiTokenVault.ApiTokenVaultException.class)
                .hasMessage("Could not update token vault.");
        assertThat(Files.readString(storagePaths.tokenVaultFile())).isEqualTo("malformed-vault");
        assertThat(Files.readString(storagePaths.tokenVaultMasterKeyFile())).isEqualTo("malformed-key");
        assertThat(Files.readString(external)).isEqualTo("external-backup-content");
    }

    @Test
    @DisplayName("An unpreservable key aborts recreation before either final is replaced")
    void recreateVault_whenExistingKeyIsNotRegular_preservesOriginalVault() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        Files.createDirectories(storagePaths.secretsDirectory());
        Files.writeString(storagePaths.tokenVaultFile(), "malformed-vault");
        Files.createDirectory(storagePaths.tokenVaultMasterKeyFile());
        var subject = new ApiTokenVault(storagePaths);

        assertThatThrownBy(subject::recreateVault)
                .isInstanceOf(ApiTokenVault.ApiTokenVaultException.class)
                .hasMessage("Could not update token vault.");
        assertThat(Files.readString(storagePaths.tokenVaultFile())).isEqualTo("malformed-vault");
        assertThat(Files.isDirectory(storagePaths.tokenVaultMasterKeyFile())).isTrue();
    }

    @Test
    @DisplayName("Published vault, key, lock, and directory paths use owner-only POSIX permissions")
    void applyTokenMutation_whenPosixPermissionsAreSupported_appliesOwnerOnlyPermissions() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        assumeTrue(storagePaths.secretsDirectory().getFileSystem().supportedFileAttributeViews().contains("posix"));
        var subject = new ApiTokenVault(storagePaths);

        CredentialTestSupport.saveToken(subject, "OPENAI_API_KEY", "secret".toCharArray());

        assertThat(Files.getPosixFilePermissions(storagePaths.secretsDirectory()))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(storagePaths.tokenVaultFile()))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(storagePaths.tokenVaultMasterKeyFile()))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(storagePaths.tokenVaultLockFile()))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    @DisplayName("Terminal close rejects reads and later mutations while preserving durable files")
    void closeSecrets_whenVaultContainsToken_rejectsNewWorkAndPreservesDurableState() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var subject = new ApiTokenVault(storagePaths);
        CredentialTestSupport.saveToken(subject, "OPENAI_API_KEY", "secret".toCharArray());
        String durableVault = Files.readString(storagePaths.tokenVaultFile());

        subject.closeSecrets();

        assertThat(subject.status("OPENAI_API_KEY"))
                .extracting(ApiCredentialStatus::source, ApiCredentialStatus::errorMessage)
                .containsExactly(ApiCredentialSource.ERROR, "Credential service is closed.");
        assertThatThrownBy(() -> CredentialTestSupport.saveToken(
                subject,
                "OPENAI_API_KEY",
                "replacement".toCharArray()
        ))
                .isInstanceOf(ApiTokenVault.ApiTokenVaultException.class)
                .hasMessage("Credential service is closed.");
        assertThat(Files.readString(storagePaths.tokenVaultFile())).isEqualTo(durableVault);
    }

    @Test
    @DisplayName("Lookup masks token-bearing toString output")
    void toString_whenLookupContainsToken_masksToken() {
        try (ApiTokenLookup lookup = ApiTokenLookup.present("OPENAI_API_KEY", "secret-token".toCharArray())) {
            assertThat(lookup.toString()).doesNotContain("secret-token").contains("<masked>");
        }
    }
}
