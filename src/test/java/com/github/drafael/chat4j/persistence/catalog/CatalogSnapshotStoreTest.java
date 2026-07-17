package com.github.drafael.chat4j.persistence.catalog;

import com.github.drafael.chat4j.persistence.CacheRootHandle;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogSnapshotStoreTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("TTS publication commits correlated immutable references with distinct compact UUIDs")
    void save_whenTtsPayloadsAreValid_publishesBothReferences() {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        var subject = new CatalogSnapshotStore(root, settings);
        var group = SpeechCatalogKeySchema.tts("elevenlabs");

        boolean saved = subject.save(group, List.of(CatalogPayload.of("[]"), CatalogPayload.of("[]")));

        assertThat(saved).isTrue();
        String modelsReference = settings.get(group.slots().getFirst().referenceKey()).orElseThrow();
        String voicesReference = settings.get(group.slots().get(1).referenceKey()).orElseThrow();
        assertThat(modelsReference).matches("tts-elevenlabs-models-[0-9a-f]{32}\\.json");
        assertThat(voicesReference).matches("tts-elevenlabs-voices-[0-9a-f]{32}\\.json");
        assertThat(snapshotId(modelsReference)).isNotEqualTo(snapshotId(voicesReference));
        CatalogSnapshotRead snapshot = subject.read(group);
        assertThat(group.slots()).allSatisfy(slot -> assertThat(snapshot.payload(slot)).isPresent());
    }

    @Test
    @DisplayName("Published snapshots inherit owner-only permissions on POSIX filesystems")
    void save_whenFilesystemSupportsPosix_publishesOwnerOnlyFile() throws Exception {
        Assumptions.assumeTrue(tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        var subject = new CatalogSnapshotStore(root, settings);
        var group = SpeechCatalogKeySchema.sttModels("deepgram");

        assertThat(subject.save(group, List.of(CatalogPayload.of("[]")))).isTrue();

        String reference = settings.get(group.slots().getFirst().referenceKey()).orElseThrow();
        assertThat(Files.getPosixFilePermissions(root.path().resolve(reference)))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    @DisplayName("Coordinated TTS candidates retry duplicate UUIDs within one operation")
    void save_whenTtsUuidSupplierRepeats_usesDistinctCandidateIds() {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        var group = SpeechCatalogKeySchema.tts("elevenlabs");
        UUID first = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID second = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        var subject = new CatalogSnapshotStore(root, settings, Clock.systemUTC(), uuidSupplier(first, first, second));

        assertThat(subject.save(group, List.of(CatalogPayload.of("[]"), CatalogPayload.of("[]")))).isTrue();

        String modelsReference = settings.get(group.slots().getFirst().referenceKey()).orElseThrow();
        String voicesReference = settings.get(group.slots().get(1).referenceKey()).orElseThrow();
        assertThat(snapshotId(modelsReference)).isEqualTo(uuidHex(first));
        assertThat(snapshotId(voicesReference)).isEqualTo(uuidHex(second));
    }

    @Test
    @DisplayName("A second-slot publication failure preserves correlated TTS authority")
    void save_whenTtsVoicesPublicationExhaustsCollisions_preservesPriorGroup() throws Exception {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        var group = SpeechCatalogKeySchema.tts("elevenlabs");
        Path cacheDirectory = root.availableRoot().orElseThrow();
        String priorModelsReference = "tts-elevenlabs-models-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.json";
        String priorVoicesReference = "tts-elevenlabs-voices-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.json";
        Path priorModels = Files.writeString(cacheDirectory.resolve(priorModelsReference), "prior-models");
        Path priorVoices = Files.writeString(cacheDirectory.resolve(priorVoicesReference), "prior-voices");
        settings.updateBatch(batch -> {
            batch.put(group.slots().getFirst().referenceKey(), priorModelsReference);
            batch.put(group.slots().get(1).referenceKey(), priorVoicesReference);
            batch.put(group.updatedAtKey(), "2026-07-15T00:00:00Z");
        });
        List<UUID> attempts = List.of(
                UUID.fromString("10000000-0000-0000-0000-000000000000"),
                UUID.fromString("20000000-0000-0000-0000-000000000000"),
                UUID.fromString("30000000-0000-0000-0000-000000000000"),
                UUID.fromString("40000000-0000-0000-0000-000000000000"),
                UUID.fromString("50000000-0000-0000-0000-000000000000"),
                UUID.fromString("60000000-0000-0000-0000-000000000000"),
                UUID.fromString("70000000-0000-0000-0000-000000000000"),
                UUID.fromString("80000000-0000-0000-0000-000000000000"),
                UUID.fromString("90000000-0000-0000-0000-000000000000")
        );
        List<Path> collidingVoices = attempts.subList(1, attempts.size()).stream()
                .map(uuid -> cacheDirectory.resolve(
                        "tts-elevenlabs-voices-%s.json".formatted(uuidHex(uuid))
                ))
                .toList();
        for (Path collision : collidingVoices) {
            Files.writeString(collision, "existing");
        }
        var subject = new CatalogSnapshotStore(
                root,
                settings,
                Clock.systemUTC(),
                uuidSupplier(attempts.toArray(UUID[]::new))
        );

        assertThat(subject.save(group, List.of(CatalogPayload.of("new-models"), CatalogPayload.of("new-voices"))))
                .isFalse();

        assertThat(settings.get(group.slots().getFirst().referenceKey())).contains(priorModelsReference);
        assertThat(settings.get(group.slots().get(1).referenceKey())).contains(priorVoicesReference);
        assertThat(settings.get(group.updatedAtKey())).contains("2026-07-15T00:00:00Z");
        assertThat(priorModels).hasContent("prior-models");
        assertThat(priorVoices).hasContent("prior-voices");
        assertThat(cacheDirectory.resolve(
                "tts-elevenlabs-models-%s.json".formatted(uuidHex(attempts.getFirst()))
        )).doesNotExist();
        assertThat(collidingVoices).allSatisfy(collision -> assertThat(collision).hasContent("existing"));
    }

    @Test
    @DisplayName("A root and settings pair has one shared snapshot store")
    void shared_whenCalledForSameRootAndSettings_returnsSameStore() {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));

        assertThat(CatalogSnapshotStore.shared(root, settings))
                .isSameAs(CatalogSnapshotStore.shared(root, settings));
    }

    @Test
    @DisplayName("Unsafe references are cache misses and remain untouched")
    void read_whenReferenceEscapesRoot_returnsEmptyPayload() {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var subject = new CatalogSnapshotStore(CacheRootHandle.of(tempDir.resolve("cache")), settings);
        var group = SpeechCatalogKeySchema.sttModels("deepgram");
        settings.put(group.slots().getFirst().referenceKey(), "../outside.json");

        assertThat(subject.read(group).payload(group.slots().getFirst())).isEmpty();
        assertThat(settings.get(group.slots().getFirst().referenceKey())).contains("../outside.json");
    }

    @Test
    @DisplayName("Orphan cleanup rejects cross-slot references and preserves unrelated files")
    void cleanupOrphans_whenReferenceTargetsWrongSlot_deletesOnlySpeechOrphan() throws Exception {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        Path cacheDirectory = root.availableRoot().orElseThrow();
        String crossSlot = "tts-elevenlabs-voices-55555555555555555555555555555555.json";
        Path orphan = cacheDirectory.resolve(crossSlot);
        Path unrelated = cacheDirectory.resolve("unrelated.json");
        Path malformedGeneratedName = cacheDirectory.resolve(
                "stt-bad--slug-models-66666666666666666666666666666666.json"
        );
        Files.writeString(orphan, "[]");
        Files.writeString(unrelated, "keep");
        Files.writeString(malformedGeneratedName, "keep");
        settings.put("chat4j.tts.catalog.elevenlabs.modelsFile", crossSlot);
        var subject = new CatalogSnapshotStore(root, settings);

        subject.cleanupOrphans();

        assertThat(orphan).doesNotExist();
        assertThat(unrelated).hasContent("keep");
        assertThat(malformedGeneratedName).hasContent("keep");
    }

    @Test
    @DisplayName("Generic Vosk model files are outside the generated snapshot grammar and remain untouched")
    void cleanupOrphans_whenGenericVoskModelFileExists_preservesUnknownFile() throws Exception {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        Path cacheDirectory = root.availableRoot().orElseThrow();
        String unknownName = "stt-vosk-models-77777777777777777777777777777777.json";
        Path unknownFile = cacheDirectory.resolve(unknownName);
        Files.writeString(unknownFile, "keep");
        settings.put("chat4j.stt.catalog.vosk.modelsFile", unknownName);
        var subject = new CatalogSnapshotStore(root, settings);

        subject.cleanupOrphans();

        assertThat(unknownFile).hasContent("keep");
    }

    @Test
    @DisplayName("Orphan cleanup never performs filesystem work on the EDT")
    void cleanupOrphans_whenCalledOnEdt_preservesOrphan() throws Exception {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        Path orphan = root.availableRoot().orElseThrow()
                .resolve("stt-deepgram-models-99999999999999999999999999999999.json");
        Files.writeString(orphan, "[]");
        var subject = new CatalogSnapshotStore(root, settings);

        SwingUtilities.invokeAndWait(subject::cleanupOrphans);

        assertThat(orphan).isRegularFile();
    }

    @Test
    @DisplayName("Orphan discovery does not retain active payloads in memory")
    void cleanupOrphans_whenActiveSnapshotExists_doesNotPreloadPayload() throws Exception {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        String reference = "stt-deepgram-models-77777777777777777777777777777777.json";
        Files.writeString(root.availableRoot().orElseThrow().resolve(reference), "[]");
        settings.put("chat4j.stt.catalog.deepgram.modelsFile", reference);
        var subject = new CatalogSnapshotStore(root, settings);

        subject.cleanupOrphans();
        CatalogGroup group = SpeechCatalogKeySchema.sttModels("deepgram");
        var snapshot = new AtomicReference<CatalogSnapshotRead>();
        SwingUtilities.invokeAndWait(() -> snapshot.set(subject.read(group)));

        assertThat(snapshot.get().payload(group.slots().getFirst())).isEmpty();
    }

    @Test
    @DisplayName("Every bounded preloaded startup snapshot remains readable on the EDT")
    void read_whenActiveGroupsArePreloaded_returnsLaterGroupOnEdt() throws Exception {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        Path cacheDirectory = root.availableRoot().orElseThrow();
        settings.updateBatch(batch -> IntStream.range(0, 300).forEach(index -> batch.put(
                "chat4j.stt.catalog.a%03d.modelsFile".formatted(index),
                "stt-a%03d-models-%032d.json".formatted(index, index)
        )));
        String targetReference = "stt-zzz-models-88888888888888888888888888888888.json";
        Files.writeString(cacheDirectory.resolve(targetReference), "[]");
        settings.put("chat4j.stt.catalog.zzz.modelsFile", targetReference);
        var subject = new CatalogSnapshotStore(root, settings);

        subject.cleanupOrphans();
        subject.preloadActiveCatalogs();
        CatalogGroup target = SpeechCatalogKeySchema.sttModels("zzz");
        var snapshot = new AtomicReference<CatalogSnapshotRead>();
        SwingUtilities.invokeAndWait(() -> snapshot.set(subject.read(target)));

        assertThat(snapshot.get().payload(target.slots().getFirst())).isPresent();
    }

    @Test
    @DisplayName("Preloading an unavailable cache root skips settings discovery")
    void preloadActiveCatalogs_whenRootIsUnavailable_skipsSettingsDiscovery() {
        CacheRootHandle root = mock(CacheRootHandle.class);
        SettingsRepository settings = mock(SettingsRepository.class);
        Object lock = new Object();
        when(root.lock()).thenReturn(lock);
        when(root.availableRoot()).thenReturn(Optional.empty());
        var subject = new CatalogSnapshotStore(root, settings);

        subject.preloadActiveCatalogs();

        verify(settings, never()).findByPrefix("chat4j.", 100_000);
    }

    @Test
    @DisplayName("A timestamp failure occurs before any snapshot artifact is created")
    void save_whenClockFails_createsNoSnapshotArtifacts() throws Exception {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        var group = SpeechCatalogKeySchema.sttModels("deepgram");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenThrow(new IllegalStateException("clock unavailable"));
        var subject = new CatalogSnapshotStore(root, settings, clock, UUID::randomUUID);

        assertThat(subject.save(group, List.of(CatalogPayload.of("[]")))).isFalse();

        assertThat(settings.get(group.slots().getFirst().referenceKey())).isEmpty();
        try (var entries = Files.list(root.path())) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    @Test
    @DisplayName("An exact post-exception pointer confirmation completes publication successfully")
    void save_whenSettingsThrowsAfterCommittedPointers_confirmsPublication() throws Exception {
        var settings = new PostCommitThrowSettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        var group = SpeechCatalogKeySchema.sttModels("deepgram");
        var subject = new CatalogSnapshotStore(root, settings);

        assertThat(subject.save(group, List.of(CatalogPayload.of("[]")))).isTrue();

        String reference = settings.get(group.slots().getFirst().referenceKey()).orElseThrow();
        assertThat(root.path().resolve(reference)).isRegularFile();
        assertThat(subject.read(group).payload(group.slots().getFirst())).isPresent();
    }

    @Test
    @DisplayName("An unconfirmable pointer commit invalidates the cached group until settings can be read again")
    void save_whenCommittedPointersCannotBeConfirmed_doesNotServeStaleCachedPayload() throws Exception {
        var settings = new UnconfirmableCommitSettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        var group = SpeechCatalogKeySchema.sttModels("deepgram");
        String priorReference = "stt-deepgram-models-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.json";
        Path priorFile = Files.writeString(root.availableRoot().orElseThrow().resolve(priorReference), "prior");
        settings.put(group.slots().getFirst().referenceKey(), priorReference);
        settings.put(group.updatedAtKey(), "2026-07-15T00:00:00Z");
        UUID candidateId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        var subject = new CatalogSnapshotStore(root, settings, Clock.systemUTC(), uuidSupplier(candidateId));
        assertThat(subject.read(group).payload(group.slots().getFirst()))
                .hasValueSatisfying(payload -> assertThat(payload.value()).isEqualTo("prior"));

        assertThat(subject.save(group, List.of(CatalogPayload.of("replacement")))).isFalse();
        assertThat(priorFile).hasContent("prior");

        settings.failReads = false;
        assertThat(subject.read(group).payload(group.slots().getFirst()))
                .hasValueSatisfying(payload -> assertThat(payload.value()).isEqualTo("replacement"));
    }

    @Test
    @DisplayName("An uncertain invalidation does not leave the removed catalog in memory")
    void invalidate_whenSettingsThrowsAfterCommit_doesNotServeStaleCachedPayload() throws Exception {
        var settings = new PostInvalidationThrowSettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        var group = SpeechCatalogKeySchema.sttModels("deepgram");
        String reference = "stt-deepgram-models-cccccccccccccccccccccccccccccccc.json";
        Files.writeString(root.availableRoot().orElseThrow().resolve(reference), "cached");
        settings.put(group.slots().getFirst().referenceKey(), reference);
        var subject = new CatalogSnapshotStore(root, settings);
        assertThat(subject.read(group).payload(group.slots().getFirst())).isPresent();

        assertThat(subject.invalidate(group, List.of())).isFalse();

        assertThat(subject.read(group).payload(group.slots().getFirst())).isEmpty();
    }

    @Test
    @DisplayName("A mismatched pointer confirmation removes its unreferenced candidate")
    void save_whenCommittedPointerIsReplaced_removesUnreferencedCandidate() throws Exception {
        var group = SpeechCatalogKeySchema.sttModels("deepgram");
        var settings = new ReplacingSettingsRepository(
                tempDir.resolve("settings.properties"),
                group.slots().getFirst().referenceKey()
        );
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        var subject = new CatalogSnapshotStore(root, settings);

        assertThat(subject.save(group, List.of(CatalogPayload.of("[]")))).isFalse();

        assertThat(settings.get(group.slots().getFirst().referenceKey())).contains("unsafe.json");
        try (var entries = Files.list(root.path())) {
            assertThat(entries.filter(Files::isRegularFile).toList()).isEmpty();
        }
    }

    @Test
    @DisplayName("A colliding final snapshot is preserved and publication retries")
    void save_whenFinalNameCollides_preservesExistingFile() throws Exception {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        var group = SpeechCatalogKeySchema.sttModels("deepgram");
        UUID collision = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID replacement = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Path existing = root.availableRoot().orElseThrow()
                .resolve("stt-deepgram-models-%s.json".formatted(uuidHex(collision)));
        Files.writeString(existing, "existing");
        var subject = new CatalogSnapshotStore(root, settings, Clock.systemUTC(), uuidSupplier(collision, replacement));

        assertThat(subject.save(group, List.of(CatalogPayload.of("[]")))).isTrue();

        assertThat(existing).hasContent("existing");
        assertThat(settings.get(group.slots().getFirst().referenceKey()))
                .contains("stt-deepgram-models-%s.json".formatted(uuidHex(replacement)));
    }

    @Test
    @DisplayName("A dangling prior reference is not reused as a new snapshot generation")
    void save_whenUuidMatchesDanglingPriorReference_retriesWithNewGeneration() {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        var group = SpeechCatalogKeySchema.sttModels("deepgram");
        UUID collision = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID replacement = UUID.fromString("44444444-4444-4444-4444-444444444444");
        String priorReference = "stt-deepgram-models-%s.json".formatted(uuidHex(collision));
        settings.put(group.slots().getFirst().referenceKey(), priorReference);
        var subject = new CatalogSnapshotStore(root, settings, Clock.systemUTC(), uuidSupplier(collision, replacement));

        assertThat(subject.save(group, List.of(CatalogPayload.of("[]")))).isTrue();

        String replacementReference = "stt-deepgram-models-%s.json".formatted(uuidHex(replacement));
        assertThat(settings.get(group.slots().getFirst().referenceKey())).contains(replacementReference);
        assertThat(root.path().resolve(priorReference)).doesNotExist();
        assertThat(root.path().resolve(replacementReference)).hasContent("[]");
    }

    @Test
    @DisplayName("A colliding private temp is never deleted by another publication attempt")
    void save_whenTempNameCollides_preservesUnownedTemp() throws Exception {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        var group = SpeechCatalogKeySchema.sttModels("deepgram");
        UUID collision = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID replacement = UUID.fromString("44444444-4444-4444-4444-444444444444");
        Path existingTemp = root.availableRoot().orElseThrow()
                .resolve(".chat4j-catalog-tmp-%s.tmp".formatted(uuidHex(collision)));
        Files.writeString(existingTemp, "other attempt");
        var subject = new CatalogSnapshotStore(root, settings, Clock.systemUTC(), uuidSupplier(collision, replacement));

        assertThat(subject.save(group, List.of(CatalogPayload.of("[]")))).isTrue();

        assertThat(existingTemp).hasContent("other attempt");
        assertThat(settings.get(group.slots().getFirst().referenceKey()))
                .contains("stt-deepgram-models-%s.json".formatted(uuidHex(replacement)));
    }

    @Test
    @DisplayName("A snapshot at the exact byte limit remains readable")
    void read_whenSnapshotIsExactlyEightMib_returnsPayload() throws Exception {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        var group = SpeechCatalogKeySchema.sttModels("deepgram");
        String reference = "stt-deepgram-models-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.json";
        Files.writeString(
                root.availableRoot().orElseThrow().resolve(reference),
                "a".repeat(CatalogPayload.MAX_BYTES),
                StandardCharsets.UTF_8
        );
        settings.put(group.slots().getFirst().referenceKey(), reference);
        var subject = new CatalogSnapshotStore(root, settings);

        CatalogSnapshotRead snapshot = subject.read(group);

        assertThat(snapshot.payload(group.slots().getFirst()))
                .hasValueSatisfying(payload -> assertThat(payload.byteLength()).isEqualTo(CatalogPayload.MAX_BYTES));
    }

    @Test
    @DisplayName("A snapshot one byte over the limit is rejected")
    void read_whenSnapshotExceedsEightMib_returnsEmptyPayload() throws Exception {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        var group = SpeechCatalogKeySchema.sttModels("deepgram");
        String reference = "stt-deepgram-models-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.json";
        Files.writeString(
                root.availableRoot().orElseThrow().resolve(reference),
                "a".repeat(CatalogPayload.MAX_BYTES + 1),
                StandardCharsets.UTF_8
        );
        settings.put(group.slots().getFirst().referenceKey(), reference);
        var subject = new CatalogSnapshotStore(root, settings);

        assertThat(subject.read(group).payload(group.slots().getFirst())).isEmpty();
    }

    @Test
    @DisplayName("Malformed UTF-8 snapshots are rejected")
    void read_whenSnapshotContainsMalformedUtf8_returnsEmptyPayload() throws Exception {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        var group = SpeechCatalogKeySchema.sttModels("deepgram");
        String reference = "stt-deepgram-models-cccccccccccccccccccccccccccccccc.json";
        Files.write(root.availableRoot().orElseThrow().resolve(reference), new byte[]{(byte) 0xc3, 0x28});
        settings.put(group.slots().getFirst().referenceKey(), reference);
        var subject = new CatalogSnapshotStore(root, settings);

        assertThat(subject.read(group).payload(group.slots().getFirst())).isEmpty();
    }

    @Test
    @DisplayName("A false save condition cannot delete a same-named file after cache root substitution")
    void saveIf_whenFalseConditionSubstitutesRoot_preservesReplacementRootFileAndPriorPointer() throws Exception {
        assertRootSubstitutionDuringCondition(false);
    }

    @Test
    @DisplayName("A true save condition cannot commit after cache root substitution")
    void saveIf_whenTrueConditionSubstitutesRoot_preservesReplacementRootFileAndPriorPointer() throws Exception {
        assertRootSubstitutionDuringCondition(true);
    }

    @Test
    @DisplayName("A rejected conditional publication preserves prior authority and removes its candidate")
    void saveIf_whenConditionRejects_preservesPriorSnapshot() throws Exception {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        var group = SpeechCatalogKeySchema.sttModels("deepgram");
        String priorReference = "stt-deepgram-models-dddddddddddddddddddddddddddddddd.json";
        Path priorFile = root.availableRoot().orElseThrow().resolve(priorReference);
        Files.writeString(priorFile, "prior");
        settings.put(group.slots().getFirst().referenceKey(), priorReference);
        settings.put(group.updatedAtKey(), "2026-07-15T00:00:00Z");
        UUID candidateId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        var subject = new CatalogSnapshotStore(root, settings, Clock.systemUTC(), uuidSupplier(candidateId));

        boolean saved = subject.saveIf(group, List.of(CatalogPayload.of("replacement")), () -> false);

        assertThat(saved).isFalse();
        assertThat(settings.get(group.slots().getFirst().referenceKey())).contains(priorReference);
        assertThat(priorFile).hasContent("prior");
        assertThat(root.path().resolve("stt-deepgram-models-%s.json".formatted(uuidHex(candidateId))))
                .doesNotExist();
    }

    @Test
    @DisplayName("Orphan cleanup deletes exact private temps and preserves generated-looking directories")
    void cleanupOrphans_whenTempAndDirectoryExist_deletesOnlyRegularTemp() throws Exception {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        Path cacheDirectory = root.availableRoot().orElseThrow();
        Path temp = cacheDirectory.resolve(".chat4j-catalog-tmp-ffffffffffffffffffffffffffffffff.tmp");
        Path directory = cacheDirectory.resolve("stt-deepgram-models-11111111111111111111111111111111.json");
        Files.writeString(temp, "temp");
        Files.createDirectory(directory);
        var subject = new CatalogSnapshotStore(root, settings);

        subject.cleanupOrphans();

        assertThat(temp).doesNotExist();
        assertThat(directory).isDirectory();
    }

    private void assertRootSubstitutionDuringCondition(boolean conditionResult) throws Exception {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var root = CacheRootHandle.of(tempDir.resolve("cache"));
        var group = SpeechCatalogKeySchema.sttModels("deepgram");
        Path cacheDirectory = root.availableRoot().orElseThrow();
        String priorReference = "stt-deepgram-models-dddddddddddddddddddddddddddddddd.json";
        Files.writeString(cacheDirectory.resolve(priorReference), "prior");
        settings.put(group.slots().getFirst().referenceKey(), priorReference);
        settings.put(group.updatedAtKey(), "2026-07-15T00:00:00Z");
        UUID candidateId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        String candidateReference = "stt-deepgram-models-%s.json".formatted(uuidHex(candidateId));
        Path displacedRoot = tempDir.resolve("displaced-cache");
        var subject = new CatalogSnapshotStore(root, settings, Clock.systemUTC(), uuidSupplier(candidateId));

        boolean saved = subject.saveIf(group, List.of(CatalogPayload.of("candidate")), () -> {
            replaceCacheRoot(cacheDirectory, displacedRoot, candidateReference);
            return conditionResult;
        });

        assertThat(saved).isFalse();
        assertThat(settings.get(group.slots().getFirst().referenceKey())).contains(priorReference);
        assertThat(cacheDirectory.resolve(candidateReference)).hasContent("unrelated");
        assertThat(displacedRoot.resolve(candidateReference)).hasContent("candidate");
        assertThat(displacedRoot.resolve(priorReference)).hasContent("prior");
    }

    private static void replaceCacheRoot(Path cacheDirectory, Path displacedRoot, String candidateReference) {
        try {
            Files.move(cacheDirectory, displacedRoot);
            Files.createDirectory(cacheDirectory);
            Files.writeString(cacheDirectory.resolve(candidateReference), "unrelated");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to substitute cache root", e);
        }
    }

    private static Supplier<UUID> uuidSupplier(UUID... values) {
        Queue<UUID> uuids = new ArrayDeque<>(List.of(values));
        return uuids::remove;
    }

    private static String uuidHex(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    private static String snapshotId(String reference) {
        return reference.substring(reference.length() - 37, reference.length() - 5);
    }

    private static final class PostCommitThrowSettingsRepository extends SettingsRepository {
        private PostCommitThrowSettingsRepository(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public boolean updateBatchIf(BooleanSupplier condition, Consumer<BatchUpdate> updates) {
            super.updateBatchIf(condition, updates);
            throw new IllegalStateException("uncertain commit");
        }
    }

    private static final class UnconfirmableCommitSettingsRepository extends SettingsRepository {
        private boolean failReads;

        private UnconfirmableCommitSettingsRepository(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public boolean updateBatchIf(BooleanSupplier condition, Consumer<BatchUpdate> updates) {
            super.updateBatchIf(condition, updates);
            failReads = true;
            throw new IllegalStateException("uncertain commit");
        }

        @Override
        public Map<String, String> getAll(Iterable<String> keys) {
            if (failReads) {
                throw new IllegalStateException("confirmation unavailable");
            }
            return super.getAll(keys);
        }
    }

    private static final class PostInvalidationThrowSettingsRepository extends SettingsRepository {
        private PostInvalidationThrowSettingsRepository(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public void updateBatch(Consumer<BatchUpdate> updates) {
            super.updateBatch(updates);
            throw new IllegalStateException("uncertain invalidation");
        }
    }

    private static final class ReplacingSettingsRepository extends SettingsRepository {
        private final String referenceKey;

        private ReplacingSettingsRepository(Path settingsFile, String referenceKey) {
            super(settingsFile);
            this.referenceKey = referenceKey;
        }

        @Override
        public boolean updateBatchIf(BooleanSupplier condition, Consumer<BatchUpdate> updates) {
            boolean committed = super.updateBatchIf(condition, updates);
            put(referenceKey, "unsafe.json");
            return committed;
        }
    }
}
