package com.github.drafael.chat4j.provider.support;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.persistence.StoragePaths;
import com.sun.nio.file.ExtendedOpenOption;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;

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
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Arrays.copyOf;
import static java.util.Arrays.fill;
import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toSet;

@Slf4j
public class ApiTokenVault {

    static final int MAX_TOKEN_BYTES = 64 * 1024;
    static final int MAX_VAULT_BYTES = 4 * 1024 * 1024;
    static final int MAX_KEY_FILE_BYTES = 1024;

    private static final String NATIVE_WINDOWS_PROVIDER = "sun.nio.fs.WindowsFileSystemProvider";
    private static final Set<String> NATIVE_POSIX_PROVIDERS = Set.of(
            "sun.nio.fs.LinuxFileSystemProvider",
            "sun.nio.fs.MacOSXFileSystemProvider"
    );
    private static final int SCHEMA_VERSION = 1;
    private static final int MASTER_KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BYTES = 16;
    private static final int GCM_TAG_BITS = GCM_TAG_BYTES * Byte.SIZE;
    private static final int LOCK_MARKER_BYTES = 32;
    private static final int MAX_RECORDS = 64;
    private static final int MAX_TOKEN_ID_CHARS = 128;
    private static final int MAX_NONCE_TEXT_CHARS = 64;
    private static final int MAX_CIPHERTEXT_TEXT_CHARS = 100_000;
    private static final int MAX_TIMESTAMP_CHARS = 64;
    private static final int MAX_BACKUP_ATTEMPTS = 1_000;
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration LOCK_RETRY_DELAY = Duration.ofMillis(50);
    private static final String VAULT_READ_ERROR_MESSAGE = "Could not read saved token vault.";
    private static final String VAULT_KEY_UNAVAILABLE_MESSAGE = "Saved token vault key is unavailable.";
    private static final String VAULT_CLOSED_MESSAGE = "Credential service is closed.";
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(JSON_FACTORY);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final ReentrantLock JVM_VAULT_LOCK = new ReentrantLock(true);
    private static final FileAttribute<?>[] NO_FILE_ATTRIBUTES = new FileAttribute<?>[0];
    private static final FileAttribute<?> OWNER_ONLY_DIRECTORY_ATTRIBUTE = PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------")
    );
    private static final FileAttribute<?> OWNER_ONLY_FILE_ATTRIBUTE = PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rw-------")
    );

    private final StoragePaths storagePaths;
    private final SecureRandom secureRandom;
    private final Object stateLock = new Object();
    private final Object directoryIdentityLock = new Object();
    private final Map<Path, FileIdentity> privateTempIdentities = new HashMap<>();
    private final ThreadLocal<LockBinding> activeLockBinding = new ThreadLocal<>();
    private VaultSnapshot snapshot = VaultSnapshot.empty();
    private long snapshotGeneration;
    private boolean closed;
    private FileIdentity secretsDirectoryIdentity;

    public ApiTokenVault(@NonNull StoragePaths storagePaths) {
        this(storagePaths, SECURE_RANDOM);
    }

    ApiTokenVault(@NonNull StoragePaths storagePaths, @NonNull SecureRandom secureRandom) {
        this.storagePaths = storagePaths;
        this.secureRandom = secureRandom;
        refreshFromDiskReadOnly();
    }

    public static ApiTokenVault defaultVault() {
        return new ApiTokenVault(StoragePaths.defaultPaths());
    }

    void refreshFromDiskReadOnly() {
        long expectedGeneration;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            expectedGeneration = snapshotGeneration;
        }
        VaultSnapshot loaded = loadSnapshot(false);
        try {
            publishSnapshotIfCurrent(loaded, expectedGeneration);
            loaded = null;
        } finally {
            clearSnapshot(loaded);
        }
    }

    public ApiTokenLookup readTokenChars(String tokenId) {
        try {
            CredentialTokenIds.validateSupportedTokenId(tokenId);
        } catch (IllegalArgumentException e) {
            return ApiTokenLookup.error(tokenId, e.getMessage());
        }
        ApiTokenRecord record;
        byte[] masterKey;
        String errorMessage;
        synchronized (stateLock) {
            if (closed) {
                return ApiTokenLookup.error(tokenId, VAULT_CLOSED_MESSAGE);
            }
            record = snapshot.records().get(tokenId);
            errorMessage = snapshot.errorMessage();
            masterKey = copy(snapshot.masterKey());
        }
        if (VAULT_READ_ERROR_MESSAGE.equals(errorMessage)) {
            clear(masterKey);
            return ApiTokenLookup.error(tokenId, errorMessage);
        }
        if (errorMessage != null) {
            clear(masterKey);
            return ApiTokenLookup.error(tokenId, VAULT_KEY_UNAVAILABLE_MESSAGE);
        }
        if (record == null) {
            clear(masterKey);
            return ApiTokenLookup.missing(tokenId);
        }
        if (masterKey == null) {
            return ApiTokenLookup.error(tokenId, VAULT_KEY_UNAVAILABLE_MESSAGE);
        }
        char[] decrypted = null;
        try {
            decrypted = decrypt(tokenId, record, masterKey);
            return ApiTokenLookup.present(tokenId, decrypted);
        } catch (Exception e) {
            log.warn("Could not decrypt saved API token {} ({}).", tokenId, e.getClass().getSimpleName());
            return ApiTokenLookup.error(tokenId, "Could not read saved token.");
        } finally {
            clear(masterKey);
            clear(decrypted);
        }
    }

    public boolean hasRecord(String tokenId) {
        CredentialTokenIds.validateSupportedTokenId(tokenId);
        synchronized (stateLock) {
            return !closed && snapshot.records().containsKey(tokenId);
        }
    }

    public ApiCredentialStatus status(String tokenId) {
        try {
            CredentialTokenIds.validateSupportedTokenId(tokenId);
        } catch (IllegalArgumentException e) {
            return new ApiCredentialStatus(ApiCredentialSource.ERROR, tokenId, e.getMessage());
        }
        synchronized (stateLock) {
            if (closed) {
                return new ApiCredentialStatus(ApiCredentialSource.ERROR, tokenId, VAULT_CLOSED_MESSAGE);
            }
            if (VAULT_READ_ERROR_MESSAGE.equals(snapshot.errorMessage())) {
                return new ApiCredentialStatus(ApiCredentialSource.ERROR, tokenId, snapshot.errorMessage());
            }
            if (snapshot.errorMessage() != null) {
                return new ApiCredentialStatus(ApiCredentialSource.ERROR, tokenId, VAULT_KEY_UNAVAILABLE_MESSAGE);
            }
            if (!snapshot.records().containsKey(tokenId)) {
                return new ApiCredentialStatus(ApiCredentialSource.MISSING, tokenId, "");
            }
        }
        try (ApiTokenLookup lookup = readTokenChars(tokenId)) {
            return switch (lookup.source()) {
                case SAVED_TOKEN -> new ApiCredentialStatus(ApiCredentialSource.SAVED_TOKEN, tokenId, "");
                case ERROR -> new ApiCredentialStatus(ApiCredentialSource.ERROR, tokenId, lookup.errorMessage());
                default -> new ApiCredentialStatus(ApiCredentialSource.MISSING, tokenId, "");
            };
        }
    }

    boolean applyTokenMutation(String canonicalTokenId, List<String> aliases, char[] token) {
        CredentialTokenIds.validateSupportedTokenId(canonicalTokenId);
        aliases.forEach(CredentialTokenIds::validateSupportedTokenId);
        return withWriteLock(() -> applyTokenMutationLocked(canonicalTokenId, aliases, token));
    }

    void recreateVault() {
        withWriteLock(() -> {
            recreateVaultLocked();
            return null;
        });
    }

    void closeSecrets() {
        VaultSnapshot discarded;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            discarded = snapshot;
            snapshot = VaultSnapshot.closed();
            snapshotGeneration++;
        }
        clear(discarded.masterKey());
    }

    static void validateTokenInput(char[] token) {
        if (token == null || isBlank(token)) {
            return;
        }
        byte[] encoded = null;
        try {
            encoded = toUtf8(token);
            if (encoded.length > MAX_TOKEN_BYTES) {
                throw new IllegalArgumentException("API token exceeds the 64 KiB limit.");
            }
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("API token contains malformed characters.");
        } finally {
            clear(encoded);
        }
    }

    static boolean isBlank(char[] token) {
        return token == null
                || token.length == 0
                || CharBuffer.wrap(token).chars().allMatch(Character::isWhitespace);
    }

    private boolean applyTokenMutationLocked(
            String canonicalTokenId,
            List<String> aliases,
            char[] token
    ) throws Exception {
        VaultSnapshot current = loadSnapshot(true);
        try {
            Map<String, ApiTokenRecord> records = new LinkedHashMap<>(current.records());
            boolean clearOverride = isBlank(token);
            boolean aliasesRemoved = aliases.stream()
                    .filter(alias -> !alias.equals(canonicalTokenId))
                    .map(records::remove)
                    .anyMatch(Objects::nonNull);
            if (clearOverride) {
                boolean changed = records.remove(canonicalTokenId) != null || aliasesRemoved;
                if (changed) {
                    FileVersion publishedVault = writeVault(
                            records,
                            current.vaultVersion(),
                            current.keyVersion()
                    );
                    publishReloadedSnapshot(publishedVault, current.keyVersion());
                } else {
                    FileVersion expectedVault = current.vaultVersion();
                    FileVersion expectedKey = current.keyVersion();
                    verifySnapshotFiles(current);
                    publishSnapshot(current);
                    current = null;
                    verifyFileVersion(storagePaths.tokenVaultFile(), expectedVault);
                    verifyFileVersion(storagePaths.tokenVaultMasterKeyFile(), expectedKey);
                }
                return changed;
            }
            ApiTokenRecord existing = records.get(canonicalTokenId);
            boolean sameToken = existing != null && tokenMatches(
                    canonicalTokenId,
                    existing,
                    token,
                    current.masterKey()
            );
            if (sameToken && !aliasesRemoved) {
                FileVersion expectedVault = current.vaultVersion();
                FileVersion expectedKey = current.keyVersion();
                verifySnapshotFiles(current);
                publishSnapshot(current);
                current = null;
                verifyFileVersion(storagePaths.tokenVaultFile(), expectedVault);
                verifyFileVersion(storagePaths.tokenVaultMasterKeyFile(), expectedKey);
                return false;
            }
            byte[] masterKey = current.masterKey();
            if (masterKey == null) {
                if (!records.isEmpty()) {
                    throw new ApiTokenVaultException(VAULT_KEY_UNAVAILABLE_MESSAGE);
                }
                masterKey = createAndPersistMasterKey(current.keyVersion());
                current = new VaultSnapshot(
                        current.records(),
                        masterKey,
                        current.errorMessage(),
                        current.vaultVersion(),
                        fileVersion(storagePaths.tokenVaultMasterKeyFile(), true)
                );
            }
            records.put(canonicalTokenId, encrypt(canonicalTokenId, token, masterKey, records.values()));
            FileVersion publishedVault = writeVault(
                    records,
                    current.vaultVersion(),
                    current.keyVersion()
            );
            publishReloadedSnapshot(publishedVault, current.keyVersion());
            return true;
        } finally {
            clearSnapshot(current);
        }
    }

    private void recreateVaultLocked() throws Exception {
        Path vaultFile = storagePaths.tokenVaultFile();
        Path keyFile = storagePaths.tokenVaultMasterKeyFile();
        FileVersion expectedVault = fileVersion(vaultFile, false);
        FileVersion expectedKey = fileVersion(keyFile, false);
        PreservedBackup vaultBackup = backupIfPresent(vaultFile, MAX_VAULT_BYTES, expectedVault);
        PreservedBackup keyBackup = backupIfPresent(keyFile, MAX_KEY_FILE_BYTES, expectedKey);
        verifyFileVersion(vaultFile, expectedVault);
        verifyFileVersion(keyFile, expectedKey);

        byte[] masterKey = new byte[MASTER_KEY_BYTES];
        secureRandom.nextBytes(masterKey);
        byte[] encodedKey = Base64.getEncoder().encode(masterKey);
        byte[] encodedVault = OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(new VaultFile(SCHEMA_VERSION, emptyMap()));
        Path keyTemp = null;
        Path vaultTemp = null;
        try {
            keyTemp = preparePrivateTemp(keyFile, encodedKey, "master-key");
            clear(validateMasterKeyFile(keyTemp, emptyMap()));
            vaultTemp = preparePrivateTemp(vaultFile, encodedVault, "token-vault");
            readVaultRecords(vaultTemp);
            verifyPreservedBackup(vaultBackup);
            verifyPreservedBackup(keyBackup);
            forceSecretsDirectory();
            verifyFileVersion(vaultFile, expectedVault);
            publishPreparedTemp(keyTemp, keyFile, expectedKey);
            keyTemp = null;
            clear(validateMasterKeyFile(keyFile, emptyMap()));
            FileVersion publishedKey = fileVersion(keyFile, true);
            verifyFileVersion(keyFile, publishedKey);
            publishPreparedTemp(vaultTemp, vaultFile, expectedVault);
            vaultTemp = null;
            publishReloadedSnapshot(fileVersion(vaultFile, true), publishedKey);
        } finally {
            deletePrivateTemp(keyTemp);
            deletePrivateTemp(vaultTemp);
            clear(masterKey);
            clear(encodedKey);
            clear(encodedVault);
        }
    }

    private void publishReloadedSnapshot(FileVersion expectedVault, FileVersion expectedKey) throws IOException {
        VaultSnapshot loaded = loadSnapshot(true);
        try {
            if (!Objects.equals(expectedVault, loaded.vaultVersion())
                    || !Objects.equals(expectedKey, loaded.keyVersion())
            ) {
                throw new IOException("Published token vault pair changed before reload.");
            }
            publishSnapshot(loaded);
            loaded = null;
            verifyFileVersion(storagePaths.tokenVaultFile(), expectedVault);
            verifyFileVersion(storagePaths.tokenVaultMasterKeyFile(), expectedKey);
        } finally {
            clearSnapshot(loaded);
        }
    }

    private void verifySnapshotFiles(VaultSnapshot expected) throws IOException {
        verifyFileVersion(storagePaths.tokenVaultFile(), expected.vaultVersion());
        verifyFileVersion(storagePaths.tokenVaultMasterKeyFile(), expected.keyVersion());
    }

    private VaultSnapshot loadSnapshot(boolean strict) {
        try {
            VaultRecords vault = readVaultState(storagePaths.tokenVaultFile());
            MasterKeyState key;
            try {
                key = readMasterKeyState(vault.records());
            } catch (Exception e) {
                if (strict) {
                    throw new ApiTokenVaultException(VAULT_KEY_UNAVAILABLE_MESSAGE);
                }
                log.warn("Could not load API token vault key status ({}).", e.getClass().getSimpleName());
                return new VaultSnapshot(
                        Map.copyOf(vault.records()),
                        null,
                        VAULT_KEY_UNAVAILABLE_MESSAGE,
                        vault.version(),
                        null
                );
            }
            try {
                verifyFileVersion(storagePaths.tokenVaultFile(), vault.version());
                verifyFileVersion(storagePaths.tokenVaultMasterKeyFile(), key.version());
                return new VaultSnapshot(
                        Map.copyOf(vault.records()),
                        key.masterKey(),
                        null,
                        vault.version(),
                        key.version()
                );
            } catch (Exception e) {
                clear(key.masterKey());
                throw e;
            }
        } catch (ApiTokenVaultException e) {
            if (strict) {
                throw e;
            }
            log.warn("Could not load API token vault status ({}).", e.getClass().getSimpleName());
            return VaultSnapshot.readError();
        } catch (Exception e) {
            if (strict) {
                throw new ApiTokenVaultException(VAULT_READ_ERROR_MESSAGE);
            }
            log.warn("Could not load API token vault status ({}).", e.getClass().getSimpleName());
            return VaultSnapshot.readError();
        }
    }

    private Map<String, ApiTokenRecord> readVaultRecords(Path vaultFile) throws IOException {
        return readVaultState(vaultFile).records();
    }

    private VaultRecords readVaultState(Path vaultFile) throws IOException {
        BoundedFile file = readBoundedRegularFile(vaultFile, MAX_VAULT_BYTES, "token vault");
        if (file == null) {
            return new VaultRecords(emptyMap(), null);
        }
        byte[] json = file.content();
        try {
            if (json.length == 0) {
                throw new IOException("Saved token vault is empty.");
            }
            VaultFile contents = OBJECT_MAPPER.readValue(json, VaultFile.class);
            if (contents.schemaVersion() != SCHEMA_VERSION || contents.records() == null) {
                throw new IOException("Saved token vault schema is invalid.");
            }
            if (contents.records().size() > MAX_RECORDS) {
                throw new IOException("Saved token vault contains too many records.");
            }
            Map<String, ApiTokenRecord> records = new LinkedHashMap<>();
            contents.records().forEach((tokenId, record) -> {
                validateRecord(tokenId, record);
                records.put(tokenId, record);
            });
            long uniqueNonceCount = records.values().stream()
                    .map(this::normalizedNonce)
                    .distinct()
                    .count();
            if (uniqueNonceCount != records.size()) {
                throw new IOException("Saved token vault contains duplicate nonces.");
            }
            return new VaultRecords(Map.copyOf(records), file.version());
        } catch (ApiTokenVaultException e) {
            throw new IOException("Saved token vault record is invalid.");
        } finally {
            clear(json);
        }
    }

    private String normalizedNonce(ApiTokenRecord record) {
        byte[] nonce = Base64.getDecoder().decode(record.nonce());
        try {
            return Base64.getEncoder().encodeToString(nonce);
        } finally {
            clear(nonce);
        }
    }

    private void validateRecord(String tokenId, ApiTokenRecord record) {
        if (tokenId == null || tokenId.length() > MAX_TOKEN_ID_CHARS) {
            throw new ApiTokenVaultException("Saved token id is invalid.");
        }
        CredentialTokenIds.validateSupportedTokenId(tokenId);
        if (record == null || !ApiTokenRecord.ALGORITHM.equals(record.algorithm())) {
            throw new ApiTokenVaultException("Unsupported token encryption algorithm.");
        }
        if (StringUtils.isBlank(record.nonce()) || record.nonce().length() > MAX_NONCE_TEXT_CHARS) {
            throw new ApiTokenVaultException("Saved token nonce is invalid.");
        }
        if (StringUtils.isBlank(record.ciphertext()) || record.ciphertext().length() > MAX_CIPHERTEXT_TEXT_CHARS) {
            throw new ApiTokenVaultException("Saved token ciphertext is invalid.");
        }
        if (StringUtils.isBlank(record.updatedAt()) || record.updatedAt().length() > MAX_TIMESTAMP_CHARS) {
            throw new ApiTokenVaultException("Saved token timestamp is invalid.");
        }
        byte[] nonce = null;
        byte[] ciphertext = null;
        try {
            nonce = Base64.getDecoder().decode(record.nonce());
            ciphertext = Base64.getDecoder().decode(record.ciphertext());
            if (nonce.length != NONCE_BYTES) {
                throw new ApiTokenVaultException("Saved token nonce is invalid.");
            }
            if (ciphertext.length < GCM_TAG_BYTES || ciphertext.length > MAX_TOKEN_BYTES + GCM_TAG_BYTES) {
                throw new ApiTokenVaultException("Saved token ciphertext is invalid.");
            }
            Instant.parse(record.updatedAt());
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new ApiTokenVaultException("Saved token record encoding is invalid.");
        } finally {
            clear(nonce);
            clear(ciphertext);
        }
    }

    private MasterKeyState readMasterKeyState(Map<String, ApiTokenRecord> records) throws IOException {
        return readMasterKeyState(storagePaths.tokenVaultMasterKeyFile(), records);
    }

    private MasterKeyState readMasterKeyState(
            Path keyFile,
            Map<String, ApiTokenRecord> records
    ) throws IOException {
        BoundedFile file = readBoundedRegularFile(keyFile, MAX_KEY_FILE_BYTES, "token vault key");
        if (file == null) {
            if (records.isEmpty()) {
                return new MasterKeyState(null, null);
            }
            throw new IOException("Saved token vault key is missing.");
        }
        byte[] encoded = file.content();
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length != MASTER_KEY_BYTES) {
                clear(decoded);
                throw new IOException("Saved token vault key has invalid length.");
            }
            return new MasterKeyState(decoded, file.version());
        } catch (IllegalArgumentException e) {
            throw new IOException("Saved token vault key is corrupt.");
        } finally {
            clear(encoded);
        }
    }

    private byte[] validateMasterKeyFile(Path keyFile, Map<String, ApiTokenRecord> records) throws IOException {
        return readMasterKeyState(keyFile, records).masterKey();
    }

    private byte[] createAndPersistMasterKey(FileVersion expectedKey) throws IOException {
        byte[] masterKey = new byte[MASTER_KEY_BYTES];
        secureRandom.nextBytes(masterKey);
        byte[] encoded = Base64.getEncoder().encode(masterKey);
        Path temp = null;
        try {
            temp = preparePrivateTemp(storagePaths.tokenVaultMasterKeyFile(), encoded, "master-key");
            clear(validateMasterKeyFile(temp, emptyMap()));
            publishPreparedTemp(temp, storagePaths.tokenVaultMasterKeyFile(), expectedKey);
            temp = null;
            clear(validateMasterKeyFile(storagePaths.tokenVaultMasterKeyFile(), emptyMap()));
            return masterKey;
        } catch (Exception e) {
            clear(masterKey);
            throw e;
        } finally {
            clear(encoded);
            deletePrivateTemp(temp);
        }
    }

    private FileVersion writeVault(
            Map<String, ApiTokenRecord> records,
            FileVersion expectedVault,
            FileVersion expectedKey
    ) throws IOException {
        byte[] json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(new VaultFile(SCHEMA_VERSION, records));
        Path temp = null;
        try {
            if (json.length > MAX_VAULT_BYTES) {
                throw new IOException("Saved token vault exceeds the size limit.");
            }
            temp = preparePrivateTemp(storagePaths.tokenVaultFile(), json, "token-vault");
            readVaultRecords(temp);
            verifyFileVersion(storagePaths.tokenVaultMasterKeyFile(), expectedKey);
            publishPreparedTemp(temp, storagePaths.tokenVaultFile(), expectedVault);
            temp = null;
            readVaultRecords(storagePaths.tokenVaultFile());
            verifyFileVersion(storagePaths.tokenVaultMasterKeyFile(), expectedKey);
            return fileVersion(storagePaths.tokenVaultFile(), true);
        } finally {
            clear(json);
            deletePrivateTemp(temp);
        }
    }

    private PreservedBackup backupIfPresent(
            Path source,
            int maxBytes,
            FileVersion expectedSource
    ) throws IOException {
        verifyFileVersion(source, expectedSource);
        if (expectedSource == null) {
            return null;
        }
        if (expectedSource.size() > maxBytes) {
            throw new IOException("Existing secret file exceeds the backup size limit.");
        }
        Path backup = nextBackupPath(source);
        copyBoundedNoFollow(source, expectedSource, backup, maxBytes);
        FileVersion backupVersion = fileVersion(backup, true);
        if (!sameFileContent(expectedSource, backupVersion)) {
            deleteIfIdentityMatches(backup, backupVersion.identity());
            throw new IOException("Token vault backup does not match the original file.");
        }
        return new PreservedBackup(backup, backupVersion);
    }

    private void verifyPreservedBackup(PreservedBackup backup) throws IOException {
        if (backup == null) {
            return;
        }
        verifyFileVersion(backup.path(), backup.version());
    }

    private Path nextBackupPath(Path source) throws IOException {
        for (int index = 0; index < MAX_BACKUP_ATTEMPTS; index++) {
            Path candidate = source.resolveSibling("%s.backup-%04d".formatted(source.getFileName(), index));
            BasicFileAttributes attributes = readAttributes(candidate);
            if (attributes == null) {
                return candidate;
            }
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw new IOException("Unsafe token vault backup path.");
            }
        }
        throw new IOException("No available token vault backup name.");
    }

    private void copyBoundedNoFollow(
            Path source,
            FileVersion expectedSource,
            Path backup,
            int maxBytes
    ) throws IOException {
        ensureStableSecretsDirectory(true);
        Set<OpenOption> sourceOptions = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        Set<OpenOption> targetOptions = Set.of(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
        );
        FileIdentity createdBackup = null;
        try (FileChannel input = FileChannel.open(source, sourceOptions);
             FileChannel output = FileChannel.open(backup, targetOptions, ownerOnlyFileAttributes())) {
            createdBackup = fileIdentity(backup, true);
            byte[] marker = new byte[LOCK_MARKER_BYTES];
            secureRandom.nextBytes(marker);
            ByteBuffer buffer = ByteBuffer.allocate(8 * 1024);
            try {
                writeAndForce(output, marker);
                ensureStableSecretsDirectory(true);
                BoundedFile pathFile = readBoundedRegularFile(backup, LOCK_MARKER_BYTES, "token vault backup");
                try {
                    if (pathFile == null
                            || !createdBackup.matches(pathFile.version().identity())
                            || !Arrays.equals(marker, pathFile.content())
                    ) {
                        throw new IOException("Token vault backup changed after opening.");
                    }
                } finally {
                    if (pathFile != null) {
                        clear(pathFile.content());
                    }
                }
                output.position(0);
                output.truncate(0);
                long copied = 0;
                while (input.read(buffer) >= 0) {
                    buffer.flip();
                    copied += buffer.remaining();
                    if (copied > maxBytes) {
                        throw new IOException("Existing secret file exceeds the backup size limit.");
                    }
                    while (buffer.hasRemaining()) {
                        output.write(buffer);
                    }
                    buffer.clear();
                }
                output.force(true);
                verifyFileVersion(source, expectedSource);
            } finally {
                clear(marker);
                clearBuffer(buffer);
            }
        } catch (Exception e) {
            deleteIfIdentityMatches(backup, createdBackup);
            throw e;
        }
        if (!createdBackup.matches(fileIdentity(backup, true))) {
            throw new IOException("Token vault backup identity changed.");
        }
    }

    private ApiTokenRecord encrypt(
            String tokenId,
            char[] token,
            byte[] masterKey,
            Collection<ApiTokenRecord> existingRecords
    ) {
        byte[] plaintext = null;
        byte[] nonce = uniqueNonce(existingRecords);
        try {
            plaintext = toUtf8(token);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad(tokenId));
            byte[] ciphertext = cipher.doFinal(plaintext);
            try {
                return new ApiTokenRecord(
                        ApiTokenRecord.ALGORITHM,
                        Base64.getEncoder().encodeToString(nonce),
                        Base64.getEncoder().encodeToString(ciphertext),
                        Instant.now().toString()
                );
            } finally {
                clear(ciphertext);
            }
        } catch (Exception e) {
            throw new ApiTokenVaultException("Could not encrypt saved token.");
        } finally {
            clear(plaintext);
            clear(nonce);
        }
    }

    private byte[] uniqueNonce(Collection<ApiTokenRecord> existingRecords) {
        Set<String> existingNonces = existingRecords.stream()
                .map(this::normalizedNonce)
                .collect(toSet());
        byte[] nonce = new byte[NONCE_BYTES];
        for (int attempt = 0; attempt < 100; attempt++) {
            secureRandom.nextBytes(nonce);
            if (!existingNonces.contains(Base64.getEncoder().encodeToString(nonce))) {
                return nonce;
            }
        }
        clear(nonce);
        throw new ApiTokenVaultException("Could not generate a unique token nonce.");
    }

    private char[] decrypt(String tokenId, ApiTokenRecord record, byte[] masterKey) throws Exception {
        byte[] nonce = null;
        byte[] ciphertext = null;
        byte[] plaintext = null;
        try {
            nonce = Base64.getDecoder().decode(record.nonce());
            ciphertext = Base64.getDecoder().decode(record.ciphertext());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce)
            );
            cipher.updateAAD(aad(tokenId));
            plaintext = cipher.doFinal(ciphertext);
            return fromUtf8(plaintext);
        } finally {
            clear(nonce);
            clear(ciphertext);
            clear(plaintext);
        }
    }

    private boolean tokenMatches(String tokenId, ApiTokenRecord record, char[] token, byte[] masterKey) {
        if (masterKey == null) {
            return false;
        }
        char[] decrypted = null;
        try {
            decrypted = decrypt(tokenId, record, masterKey);
            return Arrays.equals(decrypted, token);
        } catch (Exception e) {
            throw new ApiTokenVaultException("Could not read existing saved token.");
        } finally {
            clear(decrypted);
        }
    }

    private byte[] aad(String tokenId) {
        return "schema=%d;tokenId=%s;algorithm=%s".formatted(SCHEMA_VERSION, tokenId, ApiTokenRecord.ALGORITHM)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] toUtf8(char[] token) throws CharacterCodingException {
        var encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer encoded = encoder.encode(CharBuffer.wrap(token));
        try {
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } finally {
            clearBuffer(encoded);
        }
    }

    private static char[] fromUtf8(byte[] bytes) throws CharacterCodingException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
        try {
            char[] chars = new char[decoded.remaining()];
            decoded.get(chars);
            return chars;
        } finally {
            clearBuffer(decoded);
        }
    }

    private <T> T withWriteLock(ThrowingSupplier<T> action) {
        ensureOpen();
        long deadlineNanos = System.nanoTime() + LOCK_TIMEOUT.toNanos();
        boolean jvmLockAcquired = false;
        try {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0 || !JVM_VAULT_LOCK.tryLock(remainingNanos, TimeUnit.NANOSECONDS)) {
                throw new ApiTokenVaultException("Timed out waiting for token vault lock.");
            }
            jvmLockAcquired = true;
            ensureSecretsDirectory();
            Path lockFile = storagePaths.tokenVaultLockFile();
            try (FileChannel channel = openLockFile(lockFile)) {
                FileLock lock = acquireLock(channel, deadlineNanos);
                if (lock == null) {
                    throw new ApiTokenVaultException("Timed out waiting for token vault lock.");
                }
                try (lock) {
                    ensureStableSecretsDirectory(true);
                    LockBinding binding = bindLockChannelToPath(channel, lockFile);
                    activeLockBinding.set(binding);
                    try {
                        T result = action.get();
                        verifyActiveLockBinding();
                        return result;
                    } finally {
                        activeLockBinding.remove();
                        clear(binding.marker());
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiTokenVaultException("Interrupted while waiting for token vault lock.");
        } catch (ApiTokenVaultException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiTokenVaultException("Could not update token vault.");
        } finally {
            if (jvmLockAcquired) {
                JVM_VAULT_LOCK.unlock();
            }
        }
    }

    private void ensureOpen() {
        synchronized (stateLock) {
            if (closed) {
                throw new ApiTokenVaultException(VAULT_CLOSED_MESSAGE);
            }
        }
    }

    private FileChannel openLockFile(Path lockFile) throws IOException {
        readRegularFileAttributes(lockFile, false);
        FileChannel channel;
        try {
            channel = FileChannel.open(lockFile, lockOpenOptions(lockFile, true), ownerOnlyFileAttributes());
        } catch (FileAlreadyExistsException e) {
            channel = FileChannel.open(lockFile, lockOpenOptions(lockFile, false));
        }
        try {
            applyOwnerOnlyFilePermissions(lockFile);
            readRegularFileAttributes(lockFile, true);
            return channel;
        } catch (Exception e) {
            channel.close();
            throw e;
        }
    }

    private static Set<OpenOption> lockOpenOptions(Path lockFile, boolean create) {
        Set<OpenOption> options = new HashSet<>();
        if (create) {
            options.add(StandardOpenOption.CREATE_NEW);
        }
        options.add(StandardOpenOption.READ);
        options.add(StandardOpenOption.WRITE);
        options.add(LinkOption.NOFOLLOW_LINKS);
        if (usesNativeWindowsFileSystem(lockFile)) {
            options.add(ExtendedOpenOption.NOSHARE_DELETE);
        }
        return Set.copyOf(options);
    }

    private LockBinding bindLockChannelToPath(FileChannel channel, Path lockFile) throws IOException {
        byte[] marker = new byte[LOCK_MARKER_BYTES];
        secureRandom.nextBytes(marker);
        try {
            channel.position(0);
            channel.truncate(0);
            writeAndForce(channel, marker);
            FileIdentity identity = fileIdentity(lockFile, true);
            boolean nativeWindows = usesNativeWindowsFileSystem(lockFile);
            if (!nativeWindows) {
                if (!usesNativePosixFileSystem(lockFile)) {
                    throw new IOException("Safe token vault lock binding is unavailable.");
                }
                verifyLockMarker(lockFile, marker, identity);
            }
            LockBinding binding = new LockBinding(
                    lockFile,
                    nativeWindows ? null : copyOf(marker, marker.length),
                    identity
            );
            clear(marker);
            return binding;
        } catch (Exception e) {
            clear(marker);
            throw e;
        }
    }

    private void verifyLockMarker(Path lockFile, byte[] marker, FileIdentity identity) throws IOException {
        BoundedFile pathFile = readBoundedRegularFile(lockFile, LOCK_MARKER_BYTES, "token vault lock");
        try {
            if (pathFile == null
                    || !identity.matches(pathFile.version().identity())
                    || !Arrays.equals(marker, pathFile.content())
            ) {
                throw new IOException("Token vault lock path changed after opening.");
            }
        } finally {
            if (pathFile != null) {
                clear(pathFile.content());
            }
        }
    }

    private void verifyActiveLockBinding() throws IOException {
        LockBinding binding = activeLockBinding.get();
        if (binding == null) {
            throw new IOException("Token vault lock binding is unavailable.");
        }
        if (!binding.identity().matches(fileIdentity(binding.path(), true))) {
            throw new IOException("Token vault lock path changed during mutation.");
        }
        if (usesNativeWindowsFileSystem(binding.path())) {
            // NOSHARE_DELETE pins the visible lock path while Windows prevents a second content read.
            return;
        }
        BoundedFile pathFile = readBoundedRegularFile(binding.path(), LOCK_MARKER_BYTES, "token vault lock");
        try {
            if (pathFile == null || !Arrays.equals(binding.marker(), pathFile.content())) {
                throw new IOException("Token vault lock path changed during mutation.");
            }
        } finally {
            if (pathFile != null) {
                clear(pathFile.content());
            }
        }
    }

    private static boolean usesNativeWindowsFileSystem(Path path) {
        return SystemUtils.IS_OS_WINDOWS
                && path.getFileSystem().equals(FileSystems.getDefault())
                && NATIVE_WINDOWS_PROVIDER.equals(path.getFileSystem().provider().getClass().getName());
    }

    private static boolean usesNativePosixFileSystem(Path path) {
        return !SystemUtils.IS_OS_WINDOWS
                && path.getFileSystem().equals(FileSystems.getDefault())
                && NATIVE_POSIX_PROVIDERS.contains(path.getFileSystem().provider().getClass().getName());
    }

    private FileLock acquireLock(FileChannel channel, long deadlineNanos) throws IOException, InterruptedException {
        long retryNanos = LOCK_RETRY_DELAY.toNanos();
        while (true) {
            FileLock lock = tryAcquireLock(channel);
            if (lock != null) {
                return lock;
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return null;
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(retryNanos, remainingNanos));
        }
    }

    private FileLock tryAcquireLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }

    private void ensureSecretsDirectory() throws IOException {
        Path directory = storagePaths.secretsDirectory();
        BasicFileAttributes attributes = readAttributes(directory);
        if (attributes == null) {
            Path parent = directory.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try {
                Files.createDirectory(directory, ownerOnlyDirectoryAttributes());
            } catch (FileAlreadyExistsException ignored) {
                // A concurrent creator won; the no-follow validation below decides whether it is safe.
            }
        }
        ensureStableSecretsDirectory(true);
        applyOwnerOnlyDirectoryPermissions(directory);
    }

    private boolean ensureStableSecretsDirectory(boolean required) throws IOException {
        Path directory = storagePaths.secretsDirectory();
        BasicFileAttributes attributes = readAttributes(directory);
        if (attributes == null) {
            if (required) {
                throw new IOException("Secrets directory is missing.");
            }
            return false;
        }
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw new IOException("Unsafe secrets directory.");
        }
        FileIdentity current = new FileIdentity(attributes.fileKey(), directory.toRealPath());
        synchronized (directoryIdentityLock) {
            if (secretsDirectoryIdentity == null) {
                secretsDirectoryIdentity = current;
            } else if (!secretsDirectoryIdentity.matches(current)) {
                throw new IOException("Secrets directory identity changed.");
            }
        }
        return true;
    }

    private Path preparePrivateTemp(Path target, byte[] content, String prefix) throws IOException {
        ensureStableSecretsDirectory(true);
        Path temp = Files.createTempFile(
                target.getParent(),
                ".%s-".formatted(prefix),
                ".tmp",
                ownerOnlyFileAttributes()
        );
        try {
            FileIdentity tempIdentity = fileIdentity(temp, true);
            privateTempIdentities.put(temp, tempIdentity);
            byte[] marker = new byte[LOCK_MARKER_BYTES];
            secureRandom.nextBytes(marker);
            try (FileChannel channel = FileChannel.open(
                    temp,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                writeAndForce(channel, marker);
                ensureStableSecretsDirectory(true);
                BoundedFile pathFile = readBoundedRegularFile(temp, LOCK_MARKER_BYTES, "private temporary file");
                try {
                    if (pathFile == null
                            || !tempIdentity.matches(pathFile.version().identity())
                            || !Arrays.equals(marker, pathFile.content())
                    ) {
                        throw new IOException("Private token vault temporary file changed after opening.");
                    }
                } finally {
                    if (pathFile != null) {
                        clear(pathFile.content());
                    }
                }
                channel.position(0);
                channel.truncate(0);
                writeAndForce(channel, content);
            } finally {
                clear(marker);
            }
            return temp;
        } catch (Exception e) {
            deletePrivateTemp(temp);
            throw e;
        }
    }

    private static void writeAndForce(FileChannel channel, byte[] content) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        channel.force(true);
    }

    private void publishPreparedTemp(Path temp, Path target, FileVersion expectedTarget) throws IOException {
        ensureStableSecretsDirectory(true);
        verifyActiveLockBinding();
        FileIdentity expectedTempIdentity = privateTempIdentities.get(temp);
        if (expectedTempIdentity == null || !expectedTempIdentity.matches(fileIdentity(temp, true))) {
            throw new IOException("Private token vault temporary file identity changed.");
        }
        FileVersion preparedTemp = fileVersion(temp, true);
        verifyFileVersion(target, expectedTarget);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        privateTempIdentities.remove(temp);
        ensureStableSecretsDirectory(true);
        FileVersion publishedTarget = fileVersion(target, true);
        if (!samePublishedFile(preparedTemp, publishedTarget)) {
            throw new IOException("Published token vault file does not match its prepared temporary file.");
        }
        forceSecretsDirectory();
        verifyActiveLockBinding();
    }

    private boolean samePublishedFile(FileVersion prepared, FileVersion published) {
        Object preparedFileKey = prepared.identity().fileKey();
        Object publishedFileKey = published.identity().fileKey();
        boolean identityMatches = preparedFileKey == null
                || publishedFileKey == null
                || preparedFileKey.equals(publishedFileKey);
        return identityMatches && sameFileContent(prepared, published);
    }

    private boolean sameFileContent(FileVersion first, FileVersion second) {
        return first.size() == second.size() && first.digest().equals(second.digest());
    }

    private BoundedFile readBoundedRegularFile(Path file, int maxBytes, String description) throws IOException {
        if (!ensureStableSecretsDirectory(false)) {
            return null;
        }
        BasicFileAttributes attributes = readRegularFileAttributes(file, false);
        if (attributes == null) {
            return null;
        }
        if (attributes.size() > maxBytes) {
            throw new IOException("Saved %s exceeds the size limit.".formatted(description));
        }
        ByteBuffer buffer = ByteBuffer.allocate(maxBytes + 1);
        try {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                    // Continue until EOF or the bound is exceeded.
                }
            }
            if (buffer.position() > maxBytes) {
                throw new IOException("Saved %s exceeds the size limit.".formatted(description));
            }
            ensureStableSecretsDirectory(true);
            BasicFileAttributes afterRead = readRegularFileAttributes(file, true);
            if (!sameIdentity(attributes, afterRead)) {
                throw new IOException("Saved %s changed while being read.".formatted(description));
            }
            byte[] content = copyOf(buffer.array(), buffer.position());
            return new BoundedFile(content, fileVersion(file, afterRead, content));
        } finally {
            clearBuffer(buffer);
        }
    }

    private BasicFileAttributes readRegularFileAttributes(Path file, boolean required) throws IOException {
        BasicFileAttributes attributes = readAttributes(file);
        if (attributes == null) {
            if (required) {
                throw new NoSuchFileException(file.toString());
            }
            return null;
        }
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw new IOException("Unsafe token vault file type.");
        }
        return attributes;
    }

    private BasicFileAttributes readAttributes(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    private FileIdentity fileIdentity(Path file, boolean required) throws IOException {
        BasicFileAttributes attributes = readRegularFileAttributes(file, required);
        return attributes == null ? null : new FileIdentity(attributes.fileKey(), file.toRealPath());
    }

    private FileVersion fileVersion(Path file, boolean required) throws IOException {
        BoundedFile bounded = readBoundedRegularFile(file, versionBound(file), "token vault file version");
        if (bounded == null) {
            if (required) {
                throw new NoSuchFileException(file.toString());
            }
            return null;
        }
        try {
            return bounded.version();
        } finally {
            clear(bounded.content());
        }
    }

    private FileVersion fileVersion(Path file, BasicFileAttributes attributes, byte[] content) throws IOException {
        return new FileVersion(
                new FileIdentity(attributes.fileKey(), file.toRealPath()),
                attributes.size(),
                digest(content)
        );
    }

    private int versionBound(Path file) {
        String fileName = file.getFileName().toString();
        if (fileName.startsWith(storagePaths.tokenVaultMasterKeyFile().getFileName().toString())) {
            return MAX_KEY_FILE_BYTES;
        }
        if (fileName.startsWith(storagePaths.tokenVaultLockFile().getFileName().toString())) {
            return LOCK_MARKER_BYTES;
        }
        return MAX_VAULT_BYTES;
    }

    private String digest(byte[] content) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable.");
        }
    }

    private void verifyFileVersion(Path file, FileVersion expected) throws IOException {
        FileVersion current = fileVersion(file, false);
        if (!Objects.equals(expected, current)) {
            throw new IOException("Token vault target changed before publication.");
        }
    }

    private boolean sameIdentity(BasicFileAttributes expected, BasicFileAttributes actual) {
        return Objects.equals(expected.fileKey(), actual.fileKey())
                && expected.size() == actual.size()
                && expected.lastModifiedTime().equals(actual.lastModifiedTime());
    }

    private void forceSecretsDirectory() throws IOException {
        if (!supportsPosixAttributes(storagePaths.secretsDirectory())) {
            return;
        }
        ensureStableSecretsDirectory(true);
        try (FileChannel channel = FileChannel.open(storagePaths.secretsDirectory(), StandardOpenOption.READ)) {
            ensureStableSecretsDirectory(true);
            channel.force(true);
        }
        ensureStableSecretsDirectory(true);
    }

    private void applyOwnerOnlyFilePermissions(Path file) throws IOException {
        applyPosixPermissionsNoFollow(file, "rw-------");
    }

    private void applyOwnerOnlyDirectoryPermissions(Path directory) throws IOException {
        applyPosixPermissionsNoFollow(directory, "rwx------");
    }

    private void applyPosixPermissionsNoFollow(Path path, String permissions) throws IOException {
        if (!supportsPosixAttributes(path)) {
            return;
        }
        PosixFileAttributeView view = Files.getFileAttributeView(
                path,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (view == null) {
            throw new IOException("POSIX file permissions are unavailable.");
        }
        view.setPermissions(PosixFilePermissions.fromString(permissions));
    }

    private FileAttribute<?>[] ownerOnlyDirectoryAttributes() {
        return supportsPosixAttributes(storagePaths.secretsDirectory())
                ? new FileAttribute<?>[] {OWNER_ONLY_DIRECTORY_ATTRIBUTE}
                : NO_FILE_ATTRIBUTES;
    }

    private FileAttribute<?>[] ownerOnlyFileAttributes() {
        return supportsPosixAttributes(storagePaths.secretsDirectory())
                ? new FileAttribute<?>[] {OWNER_ONLY_FILE_ATTRIBUTE}
                : NO_FILE_ATTRIBUTES;
    }

    private boolean supportsPosixAttributes(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private void publishSnapshotIfCurrent(VaultSnapshot loaded, long expectedGeneration) {
        VaultSnapshot discarded = null;
        synchronized (stateLock) {
            if (!closed && snapshotGeneration == expectedGeneration) {
                discarded = snapshot;
                snapshot = loaded;
                snapshotGeneration++;
                loaded = null;
            }
        }
        clearSnapshot(discarded);
        clearSnapshot(loaded);
    }

    private void publishSnapshot(VaultSnapshot loaded) {
        VaultSnapshot discarded = null;
        synchronized (stateLock) {
            if (!closed) {
                discarded = snapshot;
                snapshot = loaded;
                snapshotGeneration++;
                loaded = null;
            }
        }
        clearSnapshot(discarded);
        clearSnapshot(loaded);
    }

    private void deleteIfIdentityMatches(Path file, FileIdentity expected) {
        if (expected == null) {
            return;
        }
        try {
            ensureStableSecretsDirectory(true);
            FileIdentity current = fileIdentity(file, false);
            if (current != null && expected.matches(current)) {
                Files.delete(file);
            }
        } catch (IOException e) {
            log.warn("Could not remove an incomplete token vault backup ({}).", e.getClass().getSimpleName());
        }
    }

    private void deletePrivateTemp(Path temp) {
        if (temp == null) {
            return;
        }
        FileIdentity expected = privateTempIdentities.remove(temp);
        if (expected == null) {
            return;
        }
        try {
            ensureStableSecretsDirectory(true);
            FileIdentity current = fileIdentity(temp, false);
            if (current != null && expected.matches(current)) {
                Files.delete(temp);
            }
        } catch (IOException e) {
            log.warn("Could not remove a private token vault temporary file ({}).", e.getClass().getSimpleName());
        }
    }

    private static byte[] copy(byte[] source) {
        return source == null ? null : copyOf(source, source.length);
    }

    private static void clearSnapshot(VaultSnapshot snapshot) {
        if (snapshot != null) {
            clear(snapshot.masterKey());
        }
    }

    private static void clear(byte[] value) {
        if (value != null) {
            fill(value, (byte) 0);
        }
    }

    private static void clear(char[] value) {
        if (value != null) {
            fill(value, '\0');
        }
    }

    private static void clearBuffer(ByteBuffer buffer) {
        if (buffer != null && buffer.hasArray()) {
            fill(buffer.array(), buffer.arrayOffset(), buffer.arrayOffset() + buffer.capacity(), (byte) 0);
        }
    }

    private static void clearBuffer(CharBuffer buffer) {
        if (buffer != null && buffer.hasArray()) {
            fill(buffer.array(), buffer.arrayOffset(), buffer.arrayOffset() + buffer.capacity(), '\0');
        }
    }

    private record FileIdentity(Object fileKey, Path realPath) {
        private boolean matches(FileIdentity other) {
            return fileKey != null && other.fileKey != null
                    ? fileKey.equals(other.fileKey)
                    : realPath.equals(other.realPath);
        }
    }

    private record FileVersion(FileIdentity identity, long size, String digest) {
    }

    private record LockBinding(Path path, byte[] marker, FileIdentity identity) {
    }

    private record PreservedBackup(Path path, FileVersion version) {
    }

    private record BoundedFile(byte[] content, FileVersion version) {
    }

    private record VaultRecords(Map<String, ApiTokenRecord> records, FileVersion version) {
    }

    private record MasterKeyState(byte[] masterKey, FileVersion version) {
    }

    private record VaultSnapshot(
            Map<String, ApiTokenRecord> records,
            byte[] masterKey,
            String errorMessage,
            FileVersion vaultVersion,
            FileVersion keyVersion
    ) {
        private static VaultSnapshot empty() {
            return new VaultSnapshot(emptyMap(), null, null, null, null);
        }

        private static VaultSnapshot closed() {
            return new VaultSnapshot(emptyMap(), null, VAULT_CLOSED_MESSAGE, null, null);
        }

        private static VaultSnapshot readError() {
            return new VaultSnapshot(emptyMap(), null, VAULT_READ_ERROR_MESSAGE, null, null);
        }

        @Override
        public String toString() {
            return "VaultSnapshot[recordCount=%d, masterKey=<masked>, errorMessage=%s]"
                    .formatted(records.size(), errorMessage);
        }
    }

    private record VaultFile(int schemaVersion, Map<String, ApiTokenRecord> records) {
        @JsonCreator
        private VaultFile(
                @JsonProperty("schemaVersion") int schemaVersion,
                @JsonProperty("records") Map<String, ApiTokenRecord> records
        ) {
            this.schemaVersion = schemaVersion;
            this.records = records == null ? null : Map.copyOf(records);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    static final class ApiTokenVaultException extends RuntimeException {
        ApiTokenVaultException(String message) {
            super(message);
        }
    }
}
