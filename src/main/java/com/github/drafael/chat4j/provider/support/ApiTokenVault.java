package com.github.drafael.chat4j.provider.support;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.persistence.SecureFileStore;
import com.github.drafael.chat4j.persistence.StoragePaths;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.util.Collections.emptyMap;

@Slf4j
public class ApiTokenVault {

    private static final int SCHEMA_VERSION = 1;
    private static final int MASTER_KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration LOCK_RETRY_DELAY = Duration.ofMillis(50);
    private static final String VAULT_READ_ERROR_MESSAGE = "Could not read saved token vault.";
    private static final String VAULT_KEY_UNAVAILABLE_MESSAGE = "Saved token vault key is unavailable.";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final ConcurrentMap<Path, Object> LOCK_MONITORS = new ConcurrentHashMap<>();

    private final StoragePaths storagePaths;
    private volatile VaultSnapshot snapshot = VaultSnapshot.empty();

    public ApiTokenVault(StoragePaths storagePaths) {
        this.storagePaths = storagePaths;
        refreshFromDiskReadOnly();
    }

    public static ApiTokenVault defaultVault() {
        return new ApiTokenVault(StoragePaths.defaultPaths());
    }

    public void refreshFromDiskReadOnly() {
        snapshot = loadSnapshot(false);
    }

    public ApiTokenLookup readTokenChars(String tokenId) {
        try {
            CredentialTokenIds.validateSupportedTokenId(tokenId);
        } catch (IllegalArgumentException e) {
            return ApiTokenLookup.error(tokenId, e.getMessage());
        }
        VaultSnapshot current = snapshot;
        if (VAULT_READ_ERROR_MESSAGE.equals(current.errorMessage())) {
            return ApiTokenLookup.error(tokenId, current.errorMessage());
        }
        ApiTokenRecord record = current.records().get(tokenId);
        if (record == null) {
            return ApiTokenLookup.missing(tokenId);
        }
        if (current.errorMessage() != null || current.masterKey() == null) {
            return ApiTokenLookup.error(tokenId, VAULT_KEY_UNAVAILABLE_MESSAGE);
        }
        char[] decrypted = null;
        try {
            decrypted = decrypt(tokenId, record, current.masterKey());
            return ApiTokenLookup.present(tokenId, decrypted);
        } catch (Exception e) {
            log.warn("Could not decrypt saved API token {}: {}", tokenId, StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            return ApiTokenLookup.error(tokenId, "Could not read saved token.");
        } finally {
            if (decrypted != null) {
                Arrays.fill(decrypted, '\0');
            }
        }
    }

    public boolean hasReadableToken(String tokenId) {
        try (ApiTokenLookup lookup = readTokenChars(tokenId)) {
            return lookup.present();
        }
    }

    public boolean hasRecord(String tokenId) {
        CredentialTokenIds.validateSupportedTokenId(tokenId);
        return snapshot.records().containsKey(tokenId);
    }

    public ApiCredentialStatus status(String tokenId) {
        try {
            CredentialTokenIds.validateSupportedTokenId(tokenId);
        } catch (IllegalArgumentException e) {
            return new ApiCredentialStatus(ApiCredentialSource.ERROR, tokenId, e.getMessage());
        }
        VaultSnapshot current = snapshot;
        if (VAULT_READ_ERROR_MESSAGE.equals(current.errorMessage())) {
            return new ApiCredentialStatus(ApiCredentialSource.ERROR, tokenId, current.errorMessage());
        }
        if (!current.records().containsKey(tokenId)) {
            return new ApiCredentialStatus(ApiCredentialSource.MISSING, tokenId, "");
        }
        if (current.errorMessage() != null) {
            return new ApiCredentialStatus(ApiCredentialSource.ERROR, tokenId, VAULT_KEY_UNAVAILABLE_MESSAGE);
        }
        try (ApiTokenLookup lookup = readTokenChars(tokenId)) {
            return switch (lookup.source()) {
                case SAVED_TOKEN -> new ApiCredentialStatus(ApiCredentialSource.SAVED_TOKEN, tokenId, "");
                case ERROR -> new ApiCredentialStatus(ApiCredentialSource.ERROR, tokenId, lookup.errorMessage());
                default -> new ApiCredentialStatus(ApiCredentialSource.MISSING, tokenId, "");
            };
        }
    }

    public void recreateVault() {
        withWriteLock(() -> {
            backupIfExists(storagePaths.tokenVaultFile());
            backupIfExists(storagePaths.tokenVaultMasterKeyFile());
            snapshot = VaultSnapshot.empty();
        });
    }

    public void saveToken(String tokenId, char[] token) {
        CredentialTokenIds.validateSupportedTokenId(tokenId);
        if (token == null || token.length == 0 || StringUtils.isBlank(CharBuffer.wrap(token))) {
            deleteToken(tokenId);
            return;
        }
        withWriteLock(() -> {
            VaultSnapshot current = loadSnapshot(true);
            byte[] masterKey = current.masterKey() == null ? createAndPersistMasterKey(current) : current.masterKey();
            Map<String, ApiTokenRecord> records = new LinkedHashMap<>(current.records());
            records.put(tokenId, encrypt(tokenId, token, masterKey));
            writeVault(records);
            snapshot = new VaultSnapshot(Map.copyOf(records), Arrays.copyOf(masterKey, masterKey.length), null);
        });
    }

    public void deleteToken(String tokenId) {
        CredentialTokenIds.validateSupportedTokenId(tokenId);
        withWriteLock(() -> {
            Map<String, ApiTokenRecord> records = new LinkedHashMap<>(readVaultRecords());
            if (records.remove(tokenId) == null) {
                refreshFromDiskReadOnly();
                return;
            }
            writeVault(records);
            refreshFromDiskReadOnly();
        });
    }

    public void deleteTokens(Iterable<String> tokenIds) {
        withWriteLock(() -> {
            Map<String, ApiTokenRecord> records = new LinkedHashMap<>(readVaultRecords());
            tokenIds.forEach(tokenId -> {
                CredentialTokenIds.validateSupportedTokenId(tokenId);
                records.remove(tokenId);
            });
            writeVault(records);
            refreshFromDiskReadOnly();
        });
    }

    private VaultSnapshot loadSnapshot(boolean strict) {
        try {
            Map<String, ApiTokenRecord> records = readVaultRecords();
            try {
                byte[] masterKey = readMasterKey(records);
                return new VaultSnapshot(Map.copyOf(records), masterKey, null);
            } catch (Exception e) {
                if (strict) {
                    throw new ApiTokenVaultException(VAULT_KEY_UNAVAILABLE_MESSAGE, e);
                }
                log.warn("Could not load API token vault key status ({}).", e.getClass().getSimpleName());
                return new VaultSnapshot(Map.copyOf(records), null, VAULT_KEY_UNAVAILABLE_MESSAGE);
            }
        } catch (ApiTokenVaultException e) {
            if (strict) {
                throw e;
            }
            log.warn("Could not load API token vault status ({}).", e.getClass().getSimpleName());
            return new VaultSnapshot(emptyMap(), null, VAULT_READ_ERROR_MESSAGE);
        } catch (Exception e) {
            if (strict) {
                throw new ApiTokenVaultException(VAULT_READ_ERROR_MESSAGE, e);
            }
            log.warn("Could not load API token vault status ({}).", e.getClass().getSimpleName());
            return new VaultSnapshot(emptyMap(), null, VAULT_READ_ERROR_MESSAGE);
        }
    }

    private Map<String, ApiTokenRecord> readVaultRecords() throws IOException {
        Path vaultFile = storagePaths.tokenVaultFile();
        if (!Files.exists(vaultFile)) {
            return emptyMap();
        }
        String json = Files.readString(vaultFile, StandardCharsets.UTF_8);
        if (StringUtils.isBlank(json)) {
            return emptyMap();
        }
        VaultFile vaultFileContents = OBJECT_MAPPER.readValue(json, VaultFile.class);
        if (vaultFileContents.schemaVersion() != SCHEMA_VERSION) {
            throw new IOException("Unsupported token vault schema version.");
        }
        Map<String, ApiTokenRecord> records = new LinkedHashMap<>();
        vaultFileContents.records().forEach((tokenId, record) -> {
            CredentialTokenIds.validateSupportedTokenId(tokenId);
            if (!ApiTokenRecord.ALGORITHM.equals(record.algorithm())) {
                throw new ApiTokenVaultException("Unsupported token encryption algorithm.");
            }
            records.put(tokenId, record);
        });
        return records;
    }

    private byte[] readMasterKey(Map<String, ApiTokenRecord> records) throws IOException {
        Path masterKeyFile = storagePaths.tokenVaultMasterKeyFile();
        if (!Files.exists(masterKeyFile)) {
            if (records.isEmpty()) {
                return null;
            }
            throw new IOException("Saved token vault key is missing.");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(Files.readString(masterKeyFile, StandardCharsets.UTF_8).trim());
            if (decoded.length != MASTER_KEY_BYTES) {
                throw new IOException("Saved token vault key has invalid length.");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new IOException("Saved token vault key is corrupt.", e);
        }
    }

    private byte[] createAndPersistMasterKey(VaultSnapshot current) throws IOException {
        if (!current.records().isEmpty()) {
            throw new IOException("Saved token vault key is unavailable for existing records.");
        }
        byte[] masterKey = new byte[MASTER_KEY_BYTES];
        SECURE_RANDOM.nextBytes(masterKey);
        SecureFileStore.writeStringAtomically(
                storagePaths.tokenVaultMasterKeyFile(),
                Base64.getEncoder().encodeToString(masterKey),
                "master-key"
        );
        return masterKey;
    }

    private void backupIfExists(Path file) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        String backupName = "%s.corrupt-%d".formatted(file.getFileName(), System.currentTimeMillis());
        Files.move(file, file.resolveSibling(backupName), StandardCopyOption.REPLACE_EXISTING);
    }

    private void writeVault(Map<String, ApiTokenRecord> records) throws IOException {
        VaultFile vaultFile = new VaultFile(SCHEMA_VERSION, records);
        SecureFileStore.writeStringAtomically(
                storagePaths.tokenVaultFile(),
                OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(vaultFile),
                "token-vault"
        );
    }

    private ApiTokenRecord encrypt(String tokenId, char[] token, byte[] masterKey) {
        byte[] plaintext = toUtf8(token);
        byte[] nonce = new byte[NONCE_BYTES];
        SECURE_RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad(tokenId));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return new ApiTokenRecord(
                    ApiTokenRecord.ALGORITHM,
                    Base64.getEncoder().encodeToString(nonce),
                    Base64.getEncoder().encodeToString(ciphertext),
                    Instant.now().toString()
            );
        } catch (Exception e) {
            throw new ApiTokenVaultException("Could not encrypt saved token.", e);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private char[] decrypt(String tokenId, ApiTokenRecord record, byte[] masterKey) throws Exception {
        if (!ApiTokenRecord.ALGORITHM.equals(record.algorithm())) {
            throw new IllegalArgumentException("Unsupported token encryption algorithm.");
        }
        byte[] nonce = Base64.getDecoder().decode(record.nonce());
        byte[] ciphertext = Base64.getDecoder().decode(record.ciphertext());
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad(tokenId));
        byte[] plaintext = cipher.doFinal(ciphertext);
        try {
            return fromUtf8(plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private byte[] aad(String tokenId) {
        return "schema=%d;tokenId=%s;algorithm=%s".formatted(SCHEMA_VERSION, tokenId, ApiTokenRecord.ALGORITHM)
                .getBytes(StandardCharsets.UTF_8);
    }

    private byte[] toUtf8(char[] token) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(token));
        try {
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } finally {
            clearBuffer(encoded);
        }
    }

    private char[] fromUtf8(byte[] bytes) throws CharacterCodingException {
        CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
        try {
            char[] chars = new char[decoded.remaining()];
            decoded.get(chars);
            return chars;
        } finally {
            clearBuffer(decoded);
        }
    }

    private void clearBuffer(ByteBuffer buffer) {
        if (buffer != null && buffer.hasArray()) {
            Arrays.fill(buffer.array(), buffer.arrayOffset(), buffer.arrayOffset() + buffer.capacity(), (byte) 0);
        }
    }

    private void clearBuffer(CharBuffer buffer) {
        if (buffer != null && buffer.hasArray()) {
            Arrays.fill(buffer.array(), buffer.arrayOffset(), buffer.arrayOffset() + buffer.capacity(), '\0');
        }
    }

    private void withWriteLock(ThrowingRunnable runnable) {
        synchronized (lockMonitor()) {
            try {
                SecureFileStore.createOwnerOnlyDirectory(storagePaths.secretsDirectory());
                Path lockFile = storagePaths.tokenVaultLockFile();
                try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    SecureFileStore.applyOwnerOnlyFilePermissions(lockFile);
                    FileLock lock = acquireLock(channel);
                    if (lock == null) {
                        throw new ApiTokenVaultException("Timed out waiting for token vault lock.");
                    }
                    try (lock) {
                        runnable.run();
                    }
                }
            } catch (ApiTokenVaultException e) {
                throw e;
            } catch (Exception e) {
                throw new ApiTokenVaultException("Could not update token vault.", e);
            }
        }
    }

    private Object lockMonitor() {
        return LOCK_MONITORS.computeIfAbsent(
                storagePaths.tokenVaultLockFile().toAbsolutePath().normalize(),
                path -> new Object()
        );
    }

    private FileLock acquireLock(FileChannel channel) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(LOCK_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            FileLock lock = tryAcquireLock(channel);
            if (lock != null) {
                return lock;
            }
            Thread.sleep(LOCK_RETRY_DELAY.toMillis());
        }
        return null;
    }

    private FileLock tryAcquireLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }

    private record VaultSnapshot(Map<String, ApiTokenRecord> records, byte[] masterKey, String errorMessage) {
        static VaultSnapshot empty() {
            return new VaultSnapshot(emptyMap(), null, null);
        }

        @Override
        public String toString() {
            return "VaultSnapshot[recordCount=%d, masterKey=<masked>, errorMessage=%s]".formatted(records.size(), errorMessage);
        }
    }

    private record VaultFile(int schemaVersion, Map<String, ApiTokenRecord> records) {
        @JsonCreator
        private VaultFile(
                @JsonProperty("schemaVersion") int schemaVersion,
                @JsonProperty("records") Map<String, ApiTokenRecord> records
        ) {
            this.schemaVersion = schemaVersion;
            this.records = records == null ? emptyMap() : Map.copyOf(records);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static class ApiTokenVaultException extends RuntimeException {
        public ApiTokenVaultException(String message) {
            super(message);
        }

        public ApiTokenVaultException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
