package com.github.drafael.chat4j.persistence.catalog;

import com.github.drafael.chat4j.persistence.CacheRootHandle;
import com.github.drafael.chat4j.persistence.FileIdentity;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.exception.ExceptionUtils;

import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

/** Immutable, no-clobber speech catalog publication backed by settings pointers. */
@Slf4j
public final class CatalogSnapshotStore {

    private static final int NAME_ATTEMPTS = 8;
    private static final int ENTRY_LIMIT = 100_000;
    private static final int MAX_TIMESTAMP_LENGTH = 128;
    private static final String UUID_HEX_PATTERN = "[0-9a-f]{32}";
    private static final String PROVIDER_SLUG_PATTERN = "[a-z0-9]+(?:-[a-z0-9]+)*";
    private static final Pattern UUID_HEX = Pattern.compile(UUID_HEX_PATTERN);
    private static final Pattern FINAL_NAME = Pattern.compile(
            "(?:stt-(?!vosk-models-)(?=%s-models-)%s-models-|stt-vosk-raw-json-|tts-(?=%s-(?:models|voices)-)%s-(?:models|voices)-)%s\\.json"
                    .formatted(
                            "[a-z0-9-]{1,96}",
                            PROVIDER_SLUG_PATTERN,
                            "[a-z0-9-]{1,96}",
                            PROVIDER_SLUG_PATTERN,
                            UUID_HEX_PATTERN
                    )
    );
    private static final Pattern TEMP_NAME = Pattern.compile(
            "\\.chat4j-catalog-tmp-%s\\.tmp".formatted(UUID_HEX_PATTERN)
    );
    private static final FileAttribute<?> OWNER_ONLY_FILE_ATTRIBUTE = PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rw-------")
    );
    private static final ConcurrentMap<StoreKey, CatalogSnapshotStore> APPLICATION_STORES = new ConcurrentHashMap<>();

    private final CacheRootHandle root;
    private final SettingsRepository settings;
    private final Clock clock;
    private final Supplier<UUID> uuidSupplier;
    private final ConcurrentMap<String, CatalogSnapshotRead> cachedReads = new ConcurrentHashMap<>();

    CatalogSnapshotStore(@NonNull CacheRootHandle root, @NonNull SettingsRepository settings) {
        this(root, settings, Clock.systemUTC(), UUID::randomUUID);
    }

    /** Returns the sole shared store for the settings file's sibling cache directory. */
    public static CatalogSnapshotStore forSettings(@NonNull SettingsRepository settings) {
        Path settingsFile = settings.settingsFileIdentity();
        return shared(CacheRootHandle.of(settingsFile.getParent().resolve("cache")), settings);
    }

    /** Returns the sole shared store for a root/settings pair in the running application. */
    public static CatalogSnapshotStore shared(
            @NonNull CacheRootHandle root,
            @NonNull SettingsRepository settings
    ) {
        StoreKey key = new StoreKey(root.path(), settings.settingsFileIdentity());
        return APPLICATION_STORES.computeIfAbsent(key, ignored -> new CatalogSnapshotStore(root, settings));
    }

    CatalogSnapshotStore(
            @NonNull CacheRootHandle root,
            @NonNull SettingsRepository settings,
            @NonNull Clock clock,
            @NonNull Supplier<UUID> uuidSupplier
    ) {
        this.root = root;
        this.settings = settings;
        this.clock = clock;
        this.uuidSupplier = uuidSupplier;
    }

    public CatalogSnapshotRead read(@NonNull CatalogGroup group) {
        CatalogSnapshotRead cached = cachedReads.get(group.updatedAtKey());
        if (cached != null) {
            return cached;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            return new CatalogSnapshotRead(emptyMap(), null);
        }
        synchronized (root.lock()) {
            cached = cachedReads.get(group.updatedAtKey());
            if (cached != null) {
                return cached;
            }
            try {
                Map<String, String> settingsImage = settings.getAll(groupSettingsKeys(group));
                CatalogSnapshotRead snapshot = readFromSettingsImage(group, settingsImage);
                cacheGroup(group, snapshot);
                return snapshot;
            } catch (RuntimeException e) {
                return new CatalogSnapshotRead(emptyMap(), null);
            }
        }
    }

    public boolean save(@NonNull CatalogGroup group, @NonNull List<CatalogPayload> payloads) {
        return saveIf(group, payloads, () -> true);
    }

    public boolean saveIf(
            @NonNull CatalogGroup group,
            @NonNull List<CatalogPayload> payloads,
            @NonNull BooleanSupplier condition
    ) {
        Validate.isTrue(group.slots().size() == payloads.size(), "payload count must match slot count");
        Validate.isTrue(payloads.stream().noneMatch(Objects::isNull), "payloads should not contain null");
        if (SwingUtilities.isEventDispatchThread()) {
            log.warn("Catalog snapshot save rejected on the EDT");
            return false;
        }

        synchronized (root.lock()) {
            if (root.availableRoot().isEmpty()) {
                return false;
            }
            Map<String, String> priorReferences = emptyMap();
            List<Candidate> candidates = new ArrayList<>();
            Set<String> operationIds = new HashSet<>();
            boolean pointerCommitStarted = false;
            Instant updatedAt = null;
            try {
                Instant commitTimestamp = clock.instant();
                updatedAt = commitTimestamp;
                priorReferences = settings.getAll(groupReferenceKeys(group));
                Set<String> reservedReferences = new HashSet<>(priorReferences.values());
                for (int index = 0; index < group.slots().size(); index++) {
                    candidates.add(createCandidate(
                            group.slots().get(index),
                            payloads.get(index),
                            operationIds,
                            reservedReferences
                    ));
                }
                pointerCommitStarted = true;
                boolean committed = settings.updateBatchIf(
                        () -> condition.getAsBoolean() && candidatesAreOwned(candidates),
                        batch -> {
                            candidates.forEach(candidate -> batch.put(
                                    candidate.slot().referenceKey(),
                                    candidate.basename()
                            ));
                            batch.put(group.updatedAtKey(), commitTimestamp.toString());
                            group.slots().forEach(slot -> batch.remove(obsoleteInlineKey(slot)));
                        });
                if (!committed) {
                    deleteCandidates(candidates);
                    return false;
                }

                Map<String, String> authoritative = settings.getAll(groupSettingsKeys(group));
                if (!commitMatches(group, candidates, commitTimestamp, authoritative)) {
                    deleteUnreferencedCandidates(candidates, authoritative);
                    cacheGroup(group, readFromSettingsImage(group, authoritative));
                    return false;
                }
                cachePublishedGroup(group, payloads, commitTimestamp);
                deleteReplacedReferences(group, priorReferences, candidates);
                return true;
            } catch (Exception e) {
                if (!pointerCommitStarted) {
                    deleteCandidates(candidates);
                    log.warn("Catalog snapshot save failed: {}", ExceptionUtils.getMessage(e));
                    return false;
                }
                Optional<Map<String, String>> currentSettings = currentGroupSettings(group);
                if (currentSettings.isEmpty()) {
                    uncacheGroup(group);
                    log.warn("Catalog snapshot save result could not be confirmed: {}", ExceptionUtils.getMessage(e));
                    return false;
                }
                Map<String, String> authoritative = currentSettings.orElseThrow();
                deleteUnreferencedCandidates(candidates, authoritative);
                if (updatedAt != null && commitMatches(group, candidates, updatedAt, authoritative)) {
                    cachePublishedGroup(group, payloads, updatedAt);
                    deleteReplacedReferences(group, priorReferences, candidates);
                    return true;
                }
                cacheGroup(group, readFromSettingsImage(group, authoritative));
                log.warn("Catalog snapshot save failed: {}", ExceptionUtils.getMessage(e));
                return false;
            }
        }
    }

    public boolean invalidate(@NonNull CatalogGroup group, @NonNull List<String> additionalKeys) {
        if (SwingUtilities.isEventDispatchThread()) {
            log.warn("Catalog snapshot invalidation rejected on the EDT");
            return false;
        }
        synchronized (root.lock()) {
            try {
                Map<String, String> references = settings.getAll(groupReferenceKeys(group));
                settings.updateBatch(batch -> {
                    group.slots().forEach(slot -> batch.remove(slot.referenceKey()));
                    batch.remove(group.updatedAtKey());
                    additionalKeys.forEach(batch::remove);
                });
                group.slots().forEach(slot -> deleteReference(slot, references.get(slot.referenceKey())));
                cacheInvalidatedGroup(group);
                return true;
            } catch (RuntimeException e) {
                uncacheGroup(group);
                log.warn("Catalog reference invalidation failed: {}", ExceptionUtils.getMessage(e));
                return false;
            }
        }
    }

    /** Deletes only inactive recognized speech snapshots after complete settings and root discovery. */
    public void cleanupOrphans() {
        if (SwingUtilities.isEventDispatchThread()) {
            log.warn("Catalog orphan cleanup rejected on the EDT");
            return;
        }
        synchronized (root.lock()) {
            if (root.availableRoot().isEmpty()) {
                return;
            }
            try {
                Map<String, String> settingsImage = settings.findByPrefix("chat4j.", ENTRY_LIMIT);
                Set<String> active = settingsImage.entrySet().stream()
                        .filter(entry -> isActiveSpeechReference(entry.getKey(), entry.getValue()))
                        .map(Map.Entry::getValue)
                        .collect(toSet());
                List<Path> entries;
                try (Stream<Path> children = Files.list(root.path())) {
                    entries = children.limit(ENTRY_LIMIT + 1L).toList();
                }
                if (entries.size() > ENTRY_LIMIT) {
                    log.warn("Catalog orphan cleanup skipped because the entry limit was exceeded");
                    return;
                }
                List<Path> inactiveArtifacts = inactiveRecognizedArtifacts(entries, active);
                inactiveArtifacts.forEach(this::deleteRecognizedArtifact);
            } catch (Exception e) {
                log.warn("Catalog orphan cleanup skipped: {}", ExceptionUtils.getMessage(e));
            }
        }
    }

    /** Preloads bounded active payloads so Swing consumers can read without filesystem I/O. */
    public void preloadActiveCatalogs() {
        if (SwingUtilities.isEventDispatchThread()) {
            log.warn("Catalog snapshot preload rejected on the EDT");
            return;
        }
        synchronized (root.lock()) {
            if (root.availableRoot().isEmpty()) {
                cachedReads.clear();
                return;
            }
            try {
                Map<String, String> settingsImage = settings.findByPrefix("chat4j.", ENTRY_LIMIT);
                Map<String, CatalogGroup> discoveredGroups = new LinkedHashMap<>();
                settingsImage.keySet().forEach(key -> SpeechCatalogKeySchema.groupForReferenceKey(key).ifPresent(group ->
                        discoveredGroups.putIfAbsent(group.updatedAtKey(), group)));
                cachedReads.clear();
                discoveredGroups.values().forEach(group -> cacheGroup(group, readFromSettingsImage(group, settingsImage)));
            } catch (RuntimeException e) {
                cachedReads.clear();
                log.warn("Catalog snapshot preload skipped: {}", ExceptionUtils.getMessage(e));
            }
        }
    }

    private CatalogSnapshotRead readFromSettingsImage(CatalogGroup group, Map<String, String> settingsImage) {
        var payloads = new LinkedHashMap<SnapshotSlot, CatalogPayload>();
        group.slots().forEach(slot -> read(slot, settingsImage.get(slot.referenceKey()))
                .ifPresent(payload -> payloads.put(slot, payload)));
        String updatedAt = settingsImage.get(group.updatedAtKey());
        return new CatalogSnapshotRead(
                payloads,
                updatedAt != null && updatedAt.length() <= MAX_TIMESTAMP_LENGTH ? updatedAt : null
        );
    }

    private void cacheGroup(CatalogGroup group, CatalogSnapshotRead snapshot) {
        cachedReads.put(group.updatedAtKey(), snapshot);
    }

    private void uncacheGroup(CatalogGroup group) {
        cachedReads.remove(group.updatedAtKey());
    }

    private void cachePublishedGroup(CatalogGroup group, List<CatalogPayload> payloads, Instant updatedAt) {
        var published = new LinkedHashMap<SnapshotSlot, CatalogPayload>();
        for (int index = 0; index < group.slots().size(); index++) {
            published.put(group.slots().get(index), payloads.get(index));
        }
        cacheGroup(group, new CatalogSnapshotRead(published, updatedAt.toString()));
    }

    private void cacheInvalidatedGroup(CatalogGroup group) {
        cacheGroup(group, new CatalogSnapshotRead(emptyMap(), null));
    }

    private Candidate createCandidate(
            SnapshotSlot slot,
            CatalogPayload payload,
            Set<String> operationIds,
            Set<String> reservedReferences
    ) throws IOException {
        for (int attempt = 0; attempt < NAME_ATTEMPTS; attempt++) {
            String id = nextSnapshotId();
            if (!operationIds.add(id)) {
                continue;
            }
            String finalBasename = "%s%s.json".formatted(slot.filenamePrefix(), id);
            if (reservedReferences.contains(finalBasename)) {
                continue;
            }
            Path temp = root.directChild(".chat4j-catalog-tmp-%s.tmp".formatted(id)).orElseThrow();
            Path finalFile = root.directChild(finalBasename).orElseThrow();
            boolean tempOwned = false;
            try {
                try (FileChannel channel = openPrivateTemp(temp)) {
                    tempOwned = true;
                    ByteBuffer bytes = ByteBuffer.wrap(payload.bytes());
                    while (bytes.hasRemaining()) {
                        channel.write(bytes);
                    }
                }
                publishCandidate(temp, finalFile);
                FileIdentity fileIdentity = FileIdentity.regularFile(finalFile)
                        .orElseThrow(() -> new IOException("Published catalog snapshot identity is unavailable"));
                if (root.availableRoot().isEmpty()) {
                    throw new IOException("Cache root identity changed during catalog publication");
                }
                return new Candidate(slot, finalFile, fileIdentity);
            } catch (FileAlreadyExistsException e) {
                if (tempOwned) {
                    deleteQuietly(temp);
                }
            } catch (IOException | RuntimeException e) {
                if (tempOwned) {
                    deleteQuietly(temp);
                }
                throw e;
            }
        }
        throw new IOException("Unable to allocate a unique catalog snapshot name");
    }

    private void publishCandidate(Path temp, Path finalFile) throws IOException {
        boolean finalOwned = false;
        try (FileChannel source = FileChannel.open(temp, StandardOpenOption.READ);
             FileChannel target = openPrivateFinal(finalFile)) {
            finalOwned = true;
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (source.read(buffer) != -1) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    target.write(buffer);
                }
                buffer.clear();
            }
        } catch (IOException | RuntimeException e) {
            if (finalOwned) {
                deleteQuietly(finalFile);
            }
            throw e;
        }
        deleteQuietly(temp);
    }

    private FileChannel openPrivateTemp(Path temp) throws IOException {
        return openPrivateFile(temp, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
    }

    private FileChannel openPrivateFinal(Path finalFile) throws IOException {
        return openPrivateFile(finalFile, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
    }

    private FileChannel openPrivateFile(Path file, Set<StandardOpenOption> options) throws IOException {
        return file.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? FileChannel.open(file, options, OWNER_ONLY_FILE_ATTRIBUTE)
                : FileChannel.open(file, options);
    }

    private String nextSnapshotId() {
        String id = uuidSupplier.get().toString().replace("-", "");
        Validate.isTrue(UUID_HEX.matcher(id).matches(), "UUID supplier returned an invalid snapshot id");
        return id;
    }

    private Optional<CatalogPayload> read(SnapshotSlot slot, String reference) {
        if (!validReference(slot, reference)) {
            return Optional.empty();
        }
        return root.directChild(reference).filter(this::isRegularFile).flatMap(this::readBounded);
    }

    private Optional<CatalogPayload> readBounded(Path file) {
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(CatalogPayload.MAX_BYTES + 1);
            if (bytes.length > CatalogPayload.MAX_BYTES) {
                return Optional.empty();
            }
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return Optional.of(CatalogPayload.of(decoder.decode(ByteBuffer.wrap(bytes)).toString()));
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            return Optional.empty();
        }
    }

    private boolean commitMatches(
            CatalogGroup group,
            List<Candidate> candidates,
            Instant updatedAt,
            Map<String, String> authoritative
    ) {
        if (!updatedAt.toString().equals(authoritative.get(group.updatedAtKey()))) {
            return false;
        }
        return candidates.stream().allMatch(candidate ->
                candidate.basename().equals(authoritative.get(candidate.slot().referenceKey())));
    }

    private Optional<Map<String, String>> currentGroupSettings(CatalogGroup group) {
        try {
            return Optional.of(settings.getAll(groupSettingsKeys(group)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private void deleteUnreferencedCandidates(List<Candidate> candidates, Map<String, String> authoritative) {
        Set<String> references = Set.copyOf(authoritative.values());
        candidates.stream()
                .filter(candidate -> !references.contains(candidate.basename()))
                .forEach(this::deleteCandidate);
    }

    private boolean validReference(SnapshotSlot slot, String reference) {
        return CacheRootHandle.isBasename(reference)
                && reference.startsWith(slot.filenamePrefix())
                && FINAL_NAME.matcher(reference).matches();
    }

    private boolean isActiveSpeechReference(String key, String reference) {
        return SpeechCatalogKeySchema.slotForReferenceKey(key)
                .filter(slot -> validReference(slot, reference))
                .isPresent();
    }

    private void deleteReplacedReferences(
            CatalogGroup group,
            Map<String, String> priorReferences,
            List<Candidate> candidates
    ) {
        Map<SnapshotSlot, String> publishedReferences = candidates.stream()
                .collect(toMap(Candidate::slot, Candidate::basename));
        group.slots().stream()
                .filter(slot -> !Objects.equals(
                        priorReferences.get(slot.referenceKey()),
                        publishedReferences.get(slot)
                ))
                .forEach(slot -> deleteReference(slot, priorReferences.get(slot.referenceKey())));
    }

    private void deleteReference(SnapshotSlot slot, String reference) {
        if (validReference(slot, reference)) {
            root.directChild(reference).filter(this::isRegularFile).ifPresent(this::deleteQuietly);
        }
    }

    private void deleteCandidates(List<Candidate> candidates) {
        candidates.forEach(this::deleteCandidate);
    }

    private boolean candidatesAreOwned(List<Candidate> candidates) {
        return root.availableRoot().isPresent() && candidates.stream().allMatch(this::isCandidateOwned);
    }

    private boolean isCandidateOwned(Candidate candidate) {
        return root.directChild(candidate.basename())
                .filter(expected -> expected.equals(candidate.finalFile().toAbsolutePath().normalize()))
                .flatMap(FileIdentity::regularFile)
                .filter(candidate.fileIdentity()::equals)
                .isPresent();
    }

    private void deleteCandidate(Candidate candidate) {
        if (isCandidateOwned(candidate)) {
            deleteQuietly(candidate.finalFile());
        }
    }

    private List<Path> inactiveRecognizedArtifacts(List<Path> entries, Set<String> active) throws IOException {
        List<Path> inactive = new ArrayList<>();
        for (Path entry : entries) {
            BasicFileAttributes attributes = Files.readAttributes(
                    entry,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            String name = entry.getFileName().toString();
            boolean recognized = (FINAL_NAME.matcher(name).matches() || TEMP_NAME.matcher(name).matches())
                    && attributes.isRegularFile() && !attributes.isSymbolicLink();
            if (recognized && !active.contains(name)) {
                inactive.add(entry);
            }
        }
        return List.copyOf(inactive);
    }

    private boolean isRegularFile(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() && !attributes.isSymbolicLink();
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private void deleteRecognizedArtifact(Path file) {
        String basename = file.getFileName() == null ? "" : file.getFileName().toString();
        if (!FINAL_NAME.matcher(basename).matches() && !TEMP_NAME.matcher(basename).matches()) {
            return;
        }
        root.directChild(basename)
                .filter(expected -> expected.equals(file.toAbsolutePath().normalize()))
                .filter(this::isRegularFile)
                .ifPresent(this::deleteQuietly);
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | SecurityException e) {
            log.warn("Catalog snapshot cleanup failed: {}", ExceptionUtils.getMessage(e));
        }
    }

    private static List<String> groupReferenceKeys(CatalogGroup group) {
        return group.slots().stream().map(SnapshotSlot::referenceKey).toList();
    }

    private static List<String> groupSettingsKeys(CatalogGroup group) {
        return Stream.concat(groupReferenceKeys(group).stream(), Stream.of(group.updatedAtKey())).toList();
    }

    private static String obsoleteInlineKey(SnapshotSlot slot) {
        return slot.referenceKey().substring(0, slot.referenceKey().length() - "File".length());
    }

    private record Candidate(SnapshotSlot slot, Path finalFile, FileIdentity fileIdentity) {
        private String basename() {
            return finalFile.getFileName().toString();
        }
    }

    private record StoreKey(Path root, Path settingsFile) {
    }
}
