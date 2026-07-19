package com.github.drafael.chat4j.persistence.model;

import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.support.CodexLocalModelCache;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderModelCacheServiceTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("Priming from disk loads cached provider models into memory")
    void primeFromDisk_whenProviderHasCachedModels_loadsModelsIntoMemory() {
        var cache = new InMemoryModelCache();
        cache.put("OpenAI", Instant.parse("2026-04-10T10:00:00Z"), List.of("gpt-4.1", "gpt-4o"));

        var subject = new ProviderModelCacheService(cache, fixedClock("2026-04-10T11:00:00Z"), Duration.ofHours(12));

        subject.primeFromDisk(List.of("OpenAI"));

        assertThat(subject.getModels("OpenAI")).containsExactly("gpt-4.1", "gpt-4o");
    }

    @Test
    @DisplayName("Stale entries are refreshed when fetched time exceeds TTL")
    void shouldRefresh_whenEntryIsOlderThanTtl_returnsTrue() {
        var cache = new InMemoryModelCache();
        cache.put("Anthropic", Instant.parse("2026-04-10T00:00:00Z"), List.of("claude-sonnet-4"));

        var subject = new ProviderModelCacheService(cache, fixedClock("2026-04-10T13:00:00Z"), Duration.ofHours(12));
        subject.primeFromDisk(List.of("Anthropic"));

        boolean refreshRequired = subject.shouldRefresh("Anthropic", Duration.ofHours(6));

        assertThat(refreshRequired).isTrue();
    }

    @Test
    @DisplayName("Entries expire when their refresh TTL is reached")
    void shouldRefresh_whenEntryAgeEqualsTtl_returnsTrue() {
        var cache = new InMemoryModelCache();
        cache.put("Anthropic", Instant.parse("2026-04-10T10:00:00Z"), List.of("claude-sonnet-4"));
        var subject = new ProviderModelCacheService(
                cache,
                fixedClock("2026-04-10T16:00:00Z"),
                Duration.ofHours(12)
        );

        assertThat(subject.shouldRefresh("Anthropic", Duration.ofHours(6))).isTrue();
    }

    @Test
    @DisplayName("Cache timestamps in the future are refreshed instead of trusted indefinitely")
    void shouldRefresh_whenFetchedTimestampIsInFuture_returnsTrue() {
        var cache = new InMemoryModelCache();
        cache.put("Anthropic", Instant.MAX, List.of("claude-sonnet-4"));
        var subject = new ProviderModelCacheService(
                cache,
                fixedClock("2026-04-10T16:00:00Z"),
                Duration.ofHours(12)
        );

        assertThat(subject.shouldRefresh("Anthropic")).isTrue();
    }

    @Test
    @DisplayName("Fresh empty model snapshots use a retry backoff instead of refreshing every time")
    void shouldRefresh_whenEmptySnapshotIsRecent_waitsForRetryBackoff() {
        var cache = new InMemoryModelCache();
        cache.put("OpenAI Codex", Instant.parse("2026-04-10T11:00:00Z"), emptyList());

        var recent = new ProviderModelCacheService(cache, fixedClock("2026-04-10T11:02:00Z"), Duration.ofHours(12));
        var expired = new ProviderModelCacheService(cache, fixedClock("2026-04-10T11:06:00Z"), Duration.ofHours(12));

        assertThat(recent.shouldRefresh("OpenAI Codex", Duration.ofHours(12))).isFalse();
        assertThat(expired.shouldRefresh("OpenAI Codex", Duration.ofHours(12))).isTrue();
    }

    @Test
    @DisplayName("Invalidating a fresh disk cache keeps models visible but forces refresh")
    void shouldRefresh_whenProviderInvalidatedAfterFreshDiskLoad_returnsTrueAndKeepsModels() {
        var cache = new InMemoryModelCache();
        cache.put("OpenAI", Instant.parse("2026-04-10T10:00:00Z"), List.of("gpt-4.1", "gpt-4o"));

        var subject = new ProviderModelCacheService(cache, fixedClock("2026-04-10T10:30:00Z"), Duration.ofHours(12));

        assertThat(subject.getModels("OpenAI")).containsExactly("gpt-4.1", "gpt-4o");
        assertThat(subject.shouldRefresh("OpenAI", Duration.ofHours(12))).isFalse();

        subject.invalidate("OpenAI");

        assertThat(subject.getModels("OpenAI")).containsExactly("gpt-4.1", "gpt-4o");
        assertThat(subject.shouldRefresh("OpenAI", Duration.ofHours(12))).isTrue();
        assertThat(cache.lastWriteProvider).isEqualTo("OpenAI");
        assertThat(cache.lastWriteTimestamp).isEqualTo(Instant.EPOCH);

        var restarted = new ProviderModelCacheService(cache, fixedClock("2026-04-10T10:30:00Z"), Duration.ofHours(12));

        assertThat(restarted.isInvalidated("OpenAI")).isTrue();
        assertThat(restarted.getModels("OpenAI")).isEmpty();
        assertThat(restarted.shouldRefresh("OpenAI", Duration.ofHours(12))).isTrue();

        updateModels(subject, "OpenAI", "", List.of("gpt-4.2"));

        assertThat(subject.getModels("OpenAI")).containsExactly("gpt-4.2");
        assertThat(subject.shouldRefresh("OpenAI", Duration.ofHours(12))).isFalse();
    }

    @Test
    @DisplayName("Scope versions identify only the newest provider snapshot")
    void isScopeVersionCurrent_whenNewerVersionStarts_orCurrentVersionIsCancelled_tracksNewestSnapshot() {
        var subject = new ProviderModelCacheService(
                new InMemoryModelCache(),
                fixedClock("2026-04-10T11:00:00Z"),
                Duration.ofHours(12)
        );

        long first = subject.nextScopeVersion();
        long second = subject.nextScopeVersion();

        assertThat(subject.isScopeVersionCurrent(first)).isFalse();
        assertThat(subject.isScopeVersionCurrent(second)).isTrue();

        subject.cancelScopeVersion(second);

        assertThat(subject.isScopeVersionCurrent(second)).isFalse();
    }

    @Test
    @DisplayName("Changing provider scope after restart invalidates models from the previous endpoint")
    void synchronizeScope_whenPersistedScopeDiffers_invalidatesCachedModels() {
        var cache = new ProviderModelCache(StoragePaths.ofConfigHome(tempDir));
        cache.writeCache(
                "OpenAI",
                Instant.parse("2026-04-10T10:00:00Z"),
                "https://old.example.com/v1",
                List.of("old-endpoint-model")
        );
        var subject = new ProviderModelCacheService(cache, fixedClock("2026-04-10T11:00:00Z"), Duration.ofHours(12));

        synchronizeScope(subject, "OpenAI", "https://new.example.com/v1");

        assertThat(subject.isInvalidated("OpenAI")).isTrue();
        assertThat(subject.getModels("OpenAI")).isEmpty();
        var restarted = new ProviderModelCacheService(cache, fixedClock("2026-04-10T11:00:00Z"), Duration.ofHours(12));
        synchronizeScope(restarted, "OpenAI", "https://new.example.com/v1");
        assertThat(restarted.isInvalidated("OpenAI")).isTrue();
    }

    @Test
    @DisplayName("Equivalent endpoint trailing slashes preserve the cached model catalog")
    void synchronizeScope_whenOnlyTrailingSlashDiffers_keepsCachedModels() {
        var cache = new ProviderModelCache(StoragePaths.ofConfigHome(tempDir));
        cache.writeCache(
                "OpenAI",
                Instant.parse("2026-04-10T10:00:00Z"),
                "https://api.example.com/v1/",
                List.of("cached-model")
        );
        var subject = new ProviderModelCacheService(
                cache,
                fixedClock("2026-04-10T11:00:00Z"),
                Duration.ofHours(12)
        );

        synchronizeScope(subject, "OpenAI", "https://api.example.com/v1");

        assertThat(subject.isInvalidated("OpenAI")).isFalse();
        assertThat(subject.getModels("OpenAI")).containsExactly("cached-model");
        assertThat(subject.tryBeginRefreshIfNeeded(
                "OpenAI",
                "https://api.example.com/v1/",
                Duration.ZERO
        )).isPresent();
    }

    @Test
    @DisplayName("Older provider snapshots cannot restore a superseded cache scope")
    void synchronizeScope_whenOlderSnapshotArrivesLater_keepsNewestScope() {
        var cache = new InMemoryModelCache();
        var subject = new ProviderModelCacheService(cache, fixedClock("2026-04-10T11:00:00Z"), Duration.ofHours(12));
        subject.synchronizeScope("OpenAI", "https://new.example.com/v1", 2L);
        updateModels(subject, "OpenAI", "https://new.example.com/v1", List.of("new-endpoint-model"));

        subject.synchronizeScope("OpenAI", "https://old.example.com/v1", 1L);

        assertThat(subject.isInvalidated("OpenAI")).isFalse();
        assertThat(subject.getModels("OpenAI")).containsExactly("new-endpoint-model");
        assertThat(cache.entries.get("OpenAI").scope()).isEqualTo("https://new.example.com/v1");
    }

    @Test
    @DisplayName("A current provider snapshot commits atomically before a newer scope version starts")
    void runIfScopeVersionCurrent_whenNewVersionStartsConcurrently_serializesSnapshotCommit() throws Exception {
        var subject = new ProviderModelCacheService(
                new InMemoryModelCache(),
                fixedClock("2026-04-10T11:00:00Z"),
                Duration.ofHours(12)
        );
        long scopeVersion = subject.nextScopeVersion();
        var actionStarted = new CountDownLatch(1);
        var releaseAction = new CountDownLatch(1);
        var versionRequestStarted = new CountDownLatch(1);
        var applied = new AtomicReference<Boolean>();
        var newerScopeVersion = new AtomicReference<Long>();
        Thread commitThread = Thread.startVirtualThread(() -> applied.set(subject.runIfScopeVersionCurrent(scopeVersion, () -> {
            actionStarted.countDown();
            try {
                releaseAction.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        })));
        Thread versionThread = null;

        try {
            assertThat(actionStarted.await(2, TimeUnit.SECONDS)).isTrue();
            versionThread = Thread.startVirtualThread(() -> {
                versionRequestStarted.countDown();
                newerScopeVersion.set(subject.nextScopeVersion());
            });
            assertThat(versionRequestStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(newerScopeVersion.get()).isNull();
        } finally {
            releaseAction.countDown();
            commitThread.join(2_000);
            if (versionThread != null) {
                versionThread.join(2_000);
            }
        }

        assertThat(commitThread.isAlive()).isFalse();
        assertThat(versionThread).isNotNull();
        assertThat(versionThread.isAlive()).isFalse();
        assertThat(applied).hasValue(true);
        assertThat(newerScopeVersion).hasValue(scopeVersion + 1);
    }

    @Test
    @DisplayName("Usable model lookup atomically enforces scope and invalidation state")
    void findUsableModels_whenScopeOrValidityDiffers_hidesCachedModels() {
        var subject = new ProviderModelCacheService(
                new InMemoryModelCache(),
                fixedClock("2026-04-10T11:00:00Z"),
                Duration.ofHours(12)
        );
        synchronizeScope(subject, "OpenAI", "https://current.example.com/v1");
        updateModels(subject, "OpenAI", "https://current.example.com/v1", List.of("current-model"));

        assertThat(subject.findUsableModels("OpenAI", "https://current.example.com/v1"))
                .hasValueSatisfying(models -> assertThat(models).containsExactly("current-model"));
        assertThat(subject.findUsableModels("OpenAI", "https://other.example.com/v1")).isEmpty();

        subject.invalidate("OpenAI");

        assertThat(subject.findUsableModels("OpenAI", "https://current.example.com/v1")).isEmpty();
    }

    @Test
    @DisplayName("Provider scope and refreshed models remain valid after restart")
    void synchronizeScope_whenRefreshedModelsArePersisted_restoresScopedCache() {
        var cache = new ProviderModelCache(StoragePaths.ofConfigHome(tempDir));
        var subject = new ProviderModelCacheService(cache, fixedClock("2026-04-10T11:00:00Z"), Duration.ofHours(12));
        synchronizeScope(subject, "OpenAI", "https://new.example.com/v1");
        updateModels(subject, "OpenAI", "https://new.example.com/v1", List.of("new-endpoint-model"));

        var restarted = new ProviderModelCacheService(cache, fixedClock("2026-04-10T11:30:00Z"), Duration.ofHours(12));
        synchronizeScope(restarted, "OpenAI", "https://new.example.com/v1");

        assertThat(restarted.isInvalidated("OpenAI")).isFalse();
        assertThat(restarted.getModels("OpenAI")).containsExactly("new-endpoint-model");
        assertThat(restarted.shouldRefresh("OpenAI")).isFalse();
    }

    @Test
    @DisplayName("Malformed model cache scope metadata is ignored")
    void readCacheEntry_whenScopeMetadataIsMalformed_returnsEmpty() throws Exception {
        Path cacheDir = tempDir.resolve("chat4j").resolve("cache");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("OpenAI.txt"), """
                2026-04-10T10:00:00Z
                # scope-v1 !!!
                stale-model
                """);
        var subject = new ProviderModelCache(StoragePaths.ofConfigHome(tempDir));

        assertThat(subject.readCacheEntry("OpenAI")).isEmpty();
    }

    @Test
    @DisplayName("Malformed model cache scope input is rejected instead of being replaced")
    void writeCache_whenScopeContainsMalformedSurrogate_rejectsSnapshot() throws Exception {
        var subject = new ProviderModelCache(StoragePaths.ofConfigHome(tempDir));

        boolean written = subject.writeCache(
                "OpenAI",
                Instant.parse("2026-04-10T10:00:00Z"),
                "broken-\uD800",
                List.of("model")
        );

        assertThat(written).isFalse();
        assertThat(subject.readCacheEntry("OpenAI")).isEmpty();
        try (var entries = Files.list(tempDir.resolve("chat4j").resolve("cache"))) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    @Test
    @DisplayName("In-flight refreshes started before invalidation cannot make old models fresh")
    void update_whenRefreshStartedBeforeInvalidation_finishesAsStaleAndAllowsNewRefresh() {
        var cache = new InMemoryModelCache();
        cache.put("OpenAI", Instant.parse("2026-04-10T10:00:00Z"), List.of("old-visible-model"));

        var subject = new ProviderModelCacheService(cache, fixedClock("2026-04-10T10:30:00Z"), Duration.ofHours(12));

        ProviderModelCacheService.RefreshAttempt oldRefresh = subject.tryBeginRefresh("OpenAI", "").orElseThrow();

        subject.invalidate("OpenAI");

        assertThat(subject.isInvalidated("OpenAI")).isTrue();
        assertThat(subject.shouldRefresh("OpenAI", Duration.ofHours(12))).isTrue();
        assertThat(subject.update(oldRefresh, List.of("old-account-model"))).isFalse();
        assertThat(subject.isInvalidated("OpenAI")).isTrue();
        assertThat(subject.shouldRefresh("OpenAI", Duration.ofHours(12))).isTrue();
        assertThat(subject.getModels("OpenAI")).containsExactly("old-visible-model");
        assertThat(cache.entries.get("OpenAI").models()).isEmpty();
        assertThat(cache.entries.get("OpenAI").fetchedAt()).isEqualTo(Instant.EPOCH);

        ProviderModelCacheService.RefreshAttempt newRefresh = subject.tryBeginRefresh("OpenAI", "").orElseThrow();

        assertThat(subject.update(newRefresh, List.of("new-account-model"))).isTrue();
        assertThat(subject.isInvalidated("OpenAI")).isFalse();
        assertThat(subject.getModels("OpenAI")).containsExactly("new-account-model");
        assertThat(subject.shouldRefresh("OpenAI", Duration.ofHours(12))).isFalse();
    }

    @Test
    @DisplayName("Empty scoped model cache snapshots round-trip as empty cache entries")
    void readCacheEntry_whenScopedCacheContainsNoModels_returnsEmptyModels() {
        var cache = new ProviderModelCache(StoragePaths.ofConfigHome(tempDir));

        cache.writeCache("OpenAI", Instant.EPOCH, "", emptyList());

        assertThat(cache.readCacheEntry("OpenAI"))
                .hasValueSatisfying(snapshot -> {
                    assertThat(snapshot.fetchedAt()).isEqualTo(Instant.EPOCH);
                    assertThat(snapshot.models()).isEmpty();
                });
    }

    @Test
    @DisplayName("Short provider names can be persisted atomically")
    void writeCache_whenProviderNameIsShort_persistsSnapshot() {
        var subject = new ProviderModelCache(StoragePaths.ofConfigHome(tempDir));

        assertThat(subject.writeCache("x", Instant.parse("2026-04-10T10:00:00Z"), "", List.of("model-x"))).isTrue();
        assertThat(subject.readCacheEntry("x"))
                .hasValueSatisfying(snapshot -> assertThat(snapshot.models()).containsExactly("model-x"));
    }

    @Test
    @DisplayName("A provider cache symlink is rejected without replacing it")
    void writeCache_whenTargetIsSymlink_preservesSymlinkAndExternalTarget() throws Exception {
        Path cacheDirectory = tempDir.resolve("chat4j").resolve("cache");
        Files.createDirectories(cacheDirectory);
        Path external = tempDir.resolve("external.txt");
        Files.writeString(external, "external");
        Path cacheFile = cacheDirectory.resolve("OpenAI.txt");
        createSymlinkOrSkip(cacheFile, external);
        var subject = new ProviderModelCache(StoragePaths.ofConfigHome(tempDir));

        boolean written = subject.writeCache(
                "OpenAI",
                Instant.parse("2026-04-10T10:00:00Z"),
                "",
                List.of("model")
        );

        assertThat(written).isFalse();
        assertThat(cacheFile).isSymbolicLink();
        assertThat(external).hasContent("external");
    }

    @Test
    @DisplayName("Deleting an unsafe provider cache reports failure and preserves its target")
    void deleteCache_whenTargetIsSymlink_returnsFalseAndPreservesTarget() throws Exception {
        Path cacheDirectory = tempDir.resolve("chat4j").resolve("cache");
        Files.createDirectories(cacheDirectory);
        Path external = tempDir.resolve("external-delete.txt");
        Files.writeString(external, "external");
        Path cacheFile = cacheDirectory.resolve("OpenAI.txt");
        createSymlinkOrSkip(cacheFile, external);
        var subject = new ProviderModelCache(StoragePaths.ofConfigHome(tempDir));

        assertThat(subject.deleteCache("OpenAI")).isFalse();
        assertThat(cacheFile).isSymbolicLink();
        assertThat(external).hasContent("external");
    }

    @Test
    @DisplayName("A provider cache at exactly eight MiB can be written and read")
    void writeCache_whenPayloadIsExactlyReadLimit_roundTripsSnapshot() throws Exception {
        var subject = new ProviderModelCache(StoragePaths.ofConfigHome(tempDir));
        Instant fetchedAt = Instant.parse("2026-04-10T10:00:00Z");
        String lineSeparator = System.lineSeparator();
        int fixedBytes = "%s%s# scope-v1 %s%s"
                .formatted(fetchedAt, lineSeparator, lineSeparator, lineSeparator)
                .getBytes(StandardCharsets.UTF_8)
                .length;
        String model = "a".repeat((8 * 1024 * 1024) - fixedBytes);

        assertThat(subject.writeCache("OpenAI", fetchedAt, "", List.of(model))).isTrue();

        Path cacheFile = tempDir.resolve("chat4j").resolve("cache").resolve("OpenAI.txt");
        assertThat(Files.size(cacheFile)).isEqualTo(8 * 1024 * 1024);
        assertThat(subject.readCacheEntry("OpenAI"))
                .hasValueSatisfying(snapshot -> assertThat(snapshot.models()).containsExactly(model));
    }

    @Test
    @DisplayName("Malformed UTF-8 provider caches are rejected")
    void readCacheEntry_whenPayloadContainsMalformedUtf8_returnsEmpty() throws Exception {
        Path cacheDirectory = tempDir.resolve("chat4j").resolve("cache");
        Files.createDirectories(cacheDirectory);
        byte[] timestamp = "2026-04-10T10:00:00Z\n".getBytes(StandardCharsets.UTF_8);
        byte[] malformed = Arrays.copyOf(timestamp, timestamp.length + 2);
        malformed[timestamp.length] = (byte) 0xc3;
        malformed[timestamp.length + 1] = 0x28;
        Files.write(cacheDirectory.resolve("OpenAI.txt"), malformed);
        var subject = new ProviderModelCache(StoragePaths.ofConfigHome(tempDir));

        assertThat(subject.readCacheEntry("OpenAI")).isEmpty();
    }

    @Test
    @DisplayName("Oversized provider caches are rejected before publication")
    void writeCache_whenPayloadExceedsReadLimit_returnsFalse() {
        var subject = new ProviderModelCache(StoragePaths.ofConfigHome(tempDir));

        boolean written = subject.writeCache(
                "OpenAI",
                Instant.parse("2026-04-10T10:00:00Z"),
                "",
                List.of("x".repeat(8 * 1024 * 1024))
        );

        assertThat(written).isFalse();
        assertThat(subject.readCacheEntry("OpenAI")).isEmpty();
    }

    @Test
    @DisplayName("Legacy non-Codex invalidation markers remain readable")
    void readCacheEntry_whenLegacyCacheContainsOnlyEpochTimestamp_preservesInvalidation() throws Exception {
        Path cacheDir = tempDir.resolve("chat4j").resolve("cache");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("OpenAI.txt"), "%s%n".formatted(Instant.EPOCH));
        var subject = new ProviderModelCache(StoragePaths.ofConfigHome(tempDir));

        assertThat(subject.readCacheEntry("OpenAI"))
                .hasValueSatisfying(snapshot -> {
                    assertThat(snapshot.fetchedAt()).isEqualTo(Instant.EPOCH);
                    assertThat(snapshot.models()).isEmpty();
                    assertThat(snapshot.scope()).isNull();
                });
    }

    @Test
    @DisplayName("Legacy OpenAI Codex caches are ignored because they mixed remote and local models")
    void readCacheEntry_whenCodexCacheHasNoRemoteMarker_returnsEmpty() throws Exception {
        Path cacheDir = tempDir.resolve("chat4j").resolve("cache");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("OpenAI_Codex.txt"), """
                2026-04-10T10:00:00Z
                stale-local-model
                """);
        var subject = new ProviderModelCache(StoragePaths.ofConfigHome(tempDir));

        assertThat(subject.readCacheEntry("OpenAI Codex")).isEmpty();
    }

    @Test
    @DisplayName("Legacy OpenAI Codex invalidation markers remain invalidated")
    void readCacheEntry_whenLegacyCodexCacheWasInvalidated_preservesInvalidation() throws Exception {
        Path cacheDir = tempDir.resolve("chat4j").resolve("cache");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("OpenAI_Codex.txt"), "%s%n".formatted(Instant.EPOCH));
        var cache = new ProviderModelCache(StoragePaths.ofConfigHome(tempDir));
        var subject = new ProviderModelCacheService(cache, fixedClock("2026-04-10T11:00:00Z"), Duration.ofHours(12));

        assertThat(subject.isInvalidated("OpenAI Codex")).isTrue();
        assertThat(subject.shouldRefresh("OpenAI Codex")).isTrue();
    }

    @Test
    @DisplayName("OpenAI Codex remote caches round-trip with source metadata")
    void readCacheEntry_whenCodexRemoteCacheIsWritten_returnsRemoteModels() {
        var subject = new ProviderModelCache(StoragePaths.ofConfigHome(tempDir));

        subject.writeCache(
                "OpenAI Codex",
                Instant.parse("2026-04-10T10:00:00Z"),
                "",
                List.of("remote-codex-model")
        );

        assertThat(subject.readCacheEntry("OpenAI Codex"))
                .hasValueSatisfying(snapshot -> {
                    assertThat(snapshot.fetchedAt()).isEqualTo(Instant.parse("2026-04-10T10:00:00Z"));
                    assertThat(snapshot.models()).containsExactly("remote-codex-model");
                });
    }

    @Test
    @DisplayName("A refresh started before initial scope adoption cannot populate the scoped cache")
    void update_whenScopeIsAdoptedAfterRefreshStarts_rejectsUnscopedResult() {
        var subject = new ProviderModelCacheService(
                new InMemoryModelCache(),
                fixedClock("2026-04-10T10:30:00Z"),
                Duration.ofHours(12)
        );
        ProviderModelCacheService.RefreshAttempt unscopedAttempt = subject.tryBeginRefresh("OpenAI", "").orElseThrow();

        synchronizeScope(subject, "OpenAI", "https://api.openai.com/v1");
        ProviderModelCacheService.RefreshAttempt scopedAttempt = subject.tryBeginRefresh(
                "OpenAI",
                "https://api.openai.com/v1"
        ).orElseThrow();
        subject.clearRefreshInFlight(unscopedAttempt);

        assertThat(subject.tryBeginRefresh("OpenAI", "https://api.openai.com/v1")).isEmpty();
        assertThat(subject.update(unscopedAttempt, List.of("stale-unscoped-model"))).isFalse();
        subject.clearRefreshInFlight(scopedAttempt);
        assertThat(subject.getModels("OpenAI")).isEmpty();
        assertThat(subject.shouldRefresh("OpenAI")).isTrue();
    }

    @Test
    @DisplayName("Scope mismatches are rejected without counting as in-flight contention")
    void tryBeginRefresh_whenScopeDoesNotMatch_doesNotIncrementInFlightMetric() {
        var subject = new ProviderModelCacheService(
                new InMemoryModelCache(),
                fixedClock("2026-04-10T10:30:00Z"),
                Duration.ofHours(12)
        );
        synchronizeScope(subject, "OpenAI", "https://api.openai.com/v1");

        assertThat(subject.tryBeginRefresh("OpenAI", "https://other.example.com/v1")).isEmpty();
        assertThat(subject.metricsSnapshot().refreshInFlightRejected()).isZero();
    }

    @Test
    @DisplayName("Only one refresh for the same provider can be in-flight")
    void tryBeginRefresh_whenCalledTwice_onlyFirstCallSucceeds() {
        var cache = new InMemoryModelCache();
        cache.put("Mistral", Instant.parse("2026-04-10T10:00:00Z"), List.of("mistral-large-latest"));

        var subject = new ProviderModelCacheService(cache, fixedClock("2026-04-10T10:30:00Z"), Duration.ofHours(12));

        var first = subject.tryBeginRefresh("Mistral", "");
        var second = subject.tryBeginRefresh("Mistral", "");

        assertThat(first).isPresent();
        assertThat(second).isEmpty();

        subject.clearRefreshInFlight(first.orElseThrow());

        assertThat(subject.tryBeginRefresh("Mistral", "")).isPresent();
    }

    @Test
    @DisplayName("Empty refresh results preserve the last-known-good model catalog")
    void update_whenRefreshReturnsEmpty_keepsCachedModels() {
        var cache = new InMemoryModelCache();
        cache.put("OpenAI", Instant.parse("2026-04-10T10:00:00Z"), List.of("cached-model"));
        var subject = new ProviderModelCacheService(cache, fixedClock("2026-04-10T11:00:00Z"), Duration.ofHours(12));
        ProviderModelCacheService.RefreshAttempt refreshAttempt = subject.tryBeginRefresh("OpenAI", "").orElseThrow();

        assertThat(subject.update(refreshAttempt, List.of(" "))).isFalse();
        assertThat(subject.tryBeginRefreshIfNeeded("OpenAI", "", Duration.ofHours(12))).isEmpty();
        ProviderModelCacheService.RefreshAttempt nextAttempt = subject.tryBeginRefresh("OpenAI", "").orElseThrow();
        subject.clearRefreshInFlight(nextAttempt);

        assertThat(subject.getModels("OpenAI")).containsExactly("cached-model");
        assertThat(cache.entries.get("OpenAI").models()).containsExactly("cached-model");
        assertThat(subject.shouldRefresh("OpenAI")).isFalse();
    }

    @Test
    @DisplayName("Interrupted refreshes cannot publish model results")
    void update_whenRefreshThreadIsInterrupted_preservesInvalidatedCatalog() throws Exception {
        var cache = new InMemoryModelCache();
        cache.put("OpenAI", Instant.parse("2026-04-10T10:00:00Z"), List.of("cached-model"));
        var subject = new ProviderModelCacheService(
                cache,
                fixedClock("2026-04-10T11:00:00Z"),
                Duration.ofHours(12)
        );
        subject.invalidate("OpenAI");
        ProviderModelCacheService.RefreshAttempt attempt = subject.tryBeginRefresh("OpenAI", "").orElseThrow();
        var updated = new AtomicReference<Boolean>();
        var interrupted = new AtomicReference<Boolean>();

        Thread worker = Thread.startVirtualThread(() -> {
            Thread.currentThread().interrupt();
            updated.set(subject.update(attempt, List.of("interrupted-model")));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        worker.join(2_000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(updated).hasValue(false);
        assertThat(interrupted).hasValue(true);
        assertThat(subject.isInvalidated("OpenAI")).isTrue();
        assertThat(subject.getModels("OpenAI")).containsExactly("cached-model");
        assertThat(cache.entries.get("OpenAI").models()).isEmpty();
    }

    @Test
    @DisplayName("Refresh failures preserve cached models and retry after backoff")
    void recordRefreshFailure_whenCatalogRequestFails_keepsCachedModelsAndRetriesAfterBackoff() {
        var cache = new InMemoryModelCache();
        cache.put("OpenAI", Instant.parse("2026-04-10T10:00:00Z"), List.of("cached-model"));
        var clock = new MutableClock(Instant.parse("2026-04-10T11:00:00Z"), ZoneOffset.UTC);
        var subject = new ProviderModelCacheService(
                cache,
                clock,
                Duration.ofHours(12)
        );
        ProviderModelCacheService.RefreshAttempt attempt = subject.tryBeginRefresh("OpenAI", "").orElseThrow();

        subject.recordRefreshFailure(attempt);

        assertThat(subject.getModels("OpenAI")).containsExactly("cached-model");
        assertThat(subject.shouldRefresh("OpenAI")).isFalse();
        assertThat(subject.tryBeginRefreshIfNeeded("OpenAI", "", Duration.ofHours(12))).isEmpty();

        clock.advance(Duration.ofMinutes(5));

        assertThat(subject.shouldRefresh("OpenAI")).isTrue();
        assertThat(subject.tryBeginRefreshIfNeeded("OpenAI", "", Duration.ofHours(12))).isPresent();
    }

    @Test
    @DisplayName("Failed refresh after invalidation exposes fallback and retries after backoff")
    void recordRefreshFailure_whenInvalidatedCatalogHasFallback_makesFallbackUsableAndRetryable() {
        var cache = new InMemoryModelCache();
        cache.put("OpenAI", Instant.parse("2026-04-10T10:00:00Z"), List.of("cached-model"));
        var clock = new MutableClock(Instant.parse("2026-04-10T11:00:00Z"), ZoneOffset.UTC);
        var subject = new ProviderModelCacheService(
                cache,
                clock,
                Duration.ofHours(12)
        );
        subject.invalidate("OpenAI");
        ProviderModelCacheService.RefreshAttempt attempt = subject.tryBeginRefresh("OpenAI", "").orElseThrow();

        subject.recordRefreshFailure(attempt);

        assertThat(subject.isInvalidated("OpenAI")).isFalse();
        assertThat(subject.getModels("OpenAI")).containsExactly("cached-model");
        assertThat(subject.shouldRefresh("OpenAI")).isFalse();

        clock.advance(Duration.ofMinutes(5));

        assertThat(subject.shouldRefresh("OpenAI")).isTrue();
    }

    @Test
    @DisplayName("Completed refresh attempts cannot clear or overwrite a newer attempt")
    void update_whenCompletedAttemptIsReused_doesNotAffectNewerRefresh() {
        var subject = new ProviderModelCacheService(
                new InMemoryModelCache(),
                fixedClock("2026-04-10T10:30:00Z"),
                Duration.ofHours(12)
        );
        ProviderModelCacheService.RefreshAttempt first = subject.tryBeginRefresh("Mistral", "").orElseThrow();
        assertThat(subject.update(first, List.of("first-model"))).isTrue();
        ProviderModelCacheService.RefreshAttempt second = subject.tryBeginRefresh("Mistral", "").orElseThrow();

        subject.clearRefreshInFlight(first);
        subject.recordRefreshFailure(first);

        assertThat(subject.tryBeginRefresh("Mistral", "")).isEmpty();
        assertThat(subject.update(first, List.of("stale-model"))).isFalse();
        assertThat(subject.update(second, List.of("second-model"))).isTrue();
        assertThat(subject.getModels("Mistral")).containsExactly("second-model");
    }

    @Test
    @DisplayName("OpenAI Codex cached models are merged with local Codex CLI cache")
    void getModels_whenProviderIsOpenAiCodex_mergesLocalCodexCache() {
        var cache = new InMemoryModelCache();
        cache.put("OpenAI Codex", Instant.parse("2026-04-10T10:00:00Z"), List.of("gpt-5.3-codex"));
        var subject = new ProviderModelCacheService(
                cache,
                fixedClock("2026-04-10T11:00:00Z"),
                Duration.ofHours(12),
                false,
                () -> codexSnapshot("gpt-5.5", "gpt-5.4")
        );

        assertThat(subject.refreshCodexLocalModels())
                .contains("gpt-5.5", "gpt-5.4", "gpt-5.3-codex");
    }

    @Test
    @DisplayName("OpenAI Codex local visibility hides matching remote catalog models")
    void getModels_whenRemoteModelIsHiddenLocally_excludesHiddenModel() {
        var cache = new InMemoryModelCache();
        cache.put("OpenAI Codex", Instant.parse("2026-04-10T10:00:00Z"), List.of("hidden-remote-model"));
        var subject = new ProviderModelCacheService(
                cache,
                fixedClock("2026-04-10T11:00:00Z"),
                Duration.ofHours(12),
                false,
                () -> new CodexLocalModelCache.Snapshot(
                        List.of("visible-local-model"),
                        List.of("hidden-remote-model")
                )
        );

        assertThat(subject.refreshCodexLocalModels())
                .contains("visible-local-model")
                .doesNotContain("hidden-remote-model");
    }

    @Test
    @DisplayName("Failed OpenAI Codex local cache reads preserve the previous visibility snapshot")
    void refreshCodexLocalModels_whenLocalCacheReadFails_keepsPreviousSnapshot() {
        var localModels = new AtomicReference<>(new CodexLocalModelCache.Snapshot(
                List.of("visible-local-model"),
                List.of("hidden-remote-model")
        ));
        var cache = new InMemoryModelCache();
        cache.put("OpenAI Codex", Instant.parse("2026-04-10T10:00:00Z"), List.of("hidden-remote-model"));
        var subject = new ProviderModelCacheService(
                cache,
                fixedClock("2026-04-10T11:00:00Z"),
                Duration.ofHours(12),
                false,
                localModels::get
        );
        assertThat(subject.refreshCodexLocalModels()).doesNotContain("hidden-remote-model");

        localModels.set(new CodexLocalModelCache.Snapshot(emptyList(), emptyList(), false));

        assertThat(subject.refreshCodexLocalModels())
                .contains("visible-local-model")
                .doesNotContain("hidden-remote-model");
    }

    @Test
    @DisplayName("Refreshing OpenAI Codex local models replaces the previous local overlay")
    void refreshCodexLocalModels_whenLocalCacheChanges_replacesPreviousLocalModels() {
        var localModels = new AtomicReference<>(codexSnapshot("codex-local-before"));
        var cache = new InMemoryModelCache();
        cache.put("OpenAI Codex", Instant.parse("2026-04-10T10:00:00Z"), List.of("remote-codex-model"));
        var subject = new ProviderModelCacheService(
                cache,
                fixedClock("2026-04-10T11:00:00Z"),
                Duration.ofHours(12),
                false,
                localModels::get
        );

        assertThat(subject.refreshCodexLocalModels())
                .contains("remote-codex-model", "codex-local-before");

        localModels.set(codexSnapshot("codex-local-after"));

        assertThat(subject.refreshCodexLocalModels())
                .contains("remote-codex-model", "codex-local-after")
                .doesNotContain("codex-local-before");
        assertThat(cache.entries.get("OpenAI Codex").models()).containsExactly("remote-codex-model");
    }

    @Test
    @DisplayName("Reading OpenAI Codex models on the EDT does not access the local cache supplier")
    void getModels_whenCalledOnEdt_usesCachedCodexLocalSnapshot() throws Exception {
        var supplierCalls = new AtomicInteger();
        var subject = new ProviderModelCacheService(
                new InMemoryModelCache(),
                fixedClock("2026-04-10T11:00:00Z"),
                Duration.ofHours(12),
                false,
                () -> {
                    supplierCalls.incrementAndGet();
                    return codexSnapshot("local-codex-model");
                }
        );
        var models = new AtomicReference<List<String>>();

        SwingUtilities.invokeAndWait(() -> models.set(subject.getModels("OpenAI Codex")));

        assertThat(supplierCalls).hasValue(0);
        assertThat(models.get()).contains("gpt-5.4");
    }

    @Test
    @DisplayName("A slower stale OpenAI Codex local refresh cannot replace a newer snapshot")
    void refreshCodexLocalModels_whenRefreshesCompleteOutOfOrder_keepsNewestSnapshot() throws Exception {
        var supplierCalls = new AtomicInteger();
        var firstRefreshStarted = new CountDownLatch(1);
        var releaseFirstRefresh = new CountDownLatch(1);
        var subject = new ProviderModelCacheService(
                new InMemoryModelCache(),
                fixedClock("2026-04-10T11:00:00Z"),
                Duration.ofHours(12),
                false,
                () -> {
                    if (supplierCalls.incrementAndGet() == 1) {
                        firstRefreshStarted.countDown();
                        try {
                            releaseFirstRefresh.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return codexSnapshot("stale-local-model");
                    }
                    return codexSnapshot("new-local-model");
                }
        );

        Thread firstRefresh = Thread.startVirtualThread(subject::refreshCodexLocalModels);
        Thread secondRefresh;
        try {
            assertThat(firstRefreshStarted.await(2, TimeUnit.SECONDS)).isTrue();
            secondRefresh = Thread.startVirtualThread(subject::refreshCodexLocalModels);
            secondRefresh.join(2_000);
        } finally {
            releaseFirstRefresh.countDown();
        }
        firstRefresh.join(2_000);

        assertThat(firstRefresh.isAlive()).isFalse();
        assertThat(secondRefresh.isAlive()).isFalse();
        assertThat(subject.getModels("OpenAI Codex"))
                .contains("new-local-model")
                .doesNotContain("stale-local-model");
    }

    @Test
    @DisplayName("A newer failed Codex local refresh does not suppress an older successful refresh")
    void refreshCodexLocalModels_whenNewerRefreshFails_publishesOlderSuccessfulSnapshot() throws Exception {
        var supplierCalls = new AtomicInteger();
        var firstRefreshStarted = new CountDownLatch(1);
        var releaseFirstRefresh = new CountDownLatch(1);
        var subject = new ProviderModelCacheService(
                new InMemoryModelCache(),
                fixedClock("2026-04-10T11:00:00Z"),
                Duration.ofHours(12),
                false,
                () -> {
                    if (supplierCalls.incrementAndGet() == 1) {
                        firstRefreshStarted.countDown();
                        try {
                            releaseFirstRefresh.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return codexSnapshot("successful-local-model");
                    }
                    return new CodexLocalModelCache.Snapshot(emptyList(), emptyList(), false);
                }
        );

        Thread successfulRefresh = Thread.startVirtualThread(subject::refreshCodexLocalModels);
        try {
            assertThat(firstRefreshStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Thread failedRefresh = Thread.startVirtualThread(subject::refreshCodexLocalModels);
            failedRefresh.join(2_000);
            assertThat(failedRefresh.isAlive()).isFalse();
        } finally {
            releaseFirstRefresh.countDown();
        }
        successfulRefresh.join(2_000);

        assertThat(successfulRefresh.isAlive()).isFalse();
        assertThat(subject.getModels("OpenAI Codex")).contains("successful-local-model");
    }

    @Test
    @DisplayName("Updating provider models persists sanitized models to storage")
    void update_whenModelsAreProvided_writesSanitizedSnapshotToCacheStore() {
        var cache = new InMemoryModelCache();
        var subject = new ProviderModelCacheService(cache, fixedClock("2026-04-10T12:00:00Z"), Duration.ofHours(12));

        updateModels(subject, "OpenRouter", "", List.of("", "meta-llama-3", "meta-llama-3"));

        assertThat(subject.getModels("OpenRouter")).containsExactly("meta-llama-3");
        assertThat(cache.lastWriteProvider).isEqualTo("OpenRouter");
        assertThat(cache.lastWriteTimestamp).isEqualTo(Instant.parse("2026-04-10T12:00:00Z"));
    }

    @Test
    @DisplayName("Failed model cache writes remove stale persisted snapshots")
    void update_whenCacheWriteFails_deletesStaleDiskSnapshot() {
        var cache = new FailingWriteModelCache();
        cache.put("OpenAI", Instant.parse("2026-04-10T10:00:00Z"), List.of("stale-disk-model"));
        var subject = new ProviderModelCacheService(cache, fixedClock("2026-04-10T11:00:00Z"), Duration.ofHours(12));

        updateModels(subject, "OpenAI", "", List.of("fresh-memory-model"));

        assertThat(subject.getModels("OpenAI")).containsExactly("fresh-memory-model");
        assertThat(cache.deleted).isTrue();
        var restarted = new ProviderModelCacheService(cache, fixedClock("2026-04-10T11:00:00Z"), Duration.ofHours(12));
        assertThat(restarted.getModels("OpenAI")).isEmpty();
        assertThat(restarted.shouldRefresh("OpenAI")).isTrue();
    }

    @Test
    @DisplayName("Metrics snapshot exposes memory and disk cache activity")
    void metricsSnapshot_whenCacheIsUsed_reportsHitsMissesAndUpdates() {
        var cache = new InMemoryModelCache();
        cache.put("OpenAI", Instant.parse("2026-04-10T10:00:00Z"), List.of("gpt-4.1"));

        var subject = new ProviderModelCacheService(cache, fixedClock("2026-04-10T10:30:00Z"), Duration.ofHours(12));

        subject.primeFromDisk(List.of("OpenAI"));
        subject.getModels("OpenAI");
        updateModels(subject, "OpenAI", "", List.of("gpt-4.1", "gpt-4o"));

        ProviderModelCacheService.MetricsSnapshot metrics = subject.metricsSnapshot();
        assertThat(metrics.primeRequests()).isEqualTo(1L);
        assertThat(metrics.memoryHits()).isGreaterThanOrEqualTo(1L);
        assertThat(metrics.memoryMisses()).isGreaterThanOrEqualTo(1L);
        assertThat(metrics.diskReadsWithData()).isGreaterThanOrEqualTo(1L);
        assertThat(metrics.updates()).isEqualTo(1L);
    }

    private static void synchronizeScope(
            ProviderModelCacheService subject,
            String providerName,
            String scope
    ) {
        subject.synchronizeScope(providerName, scope, subject.nextScopeVersion());
    }

    private static void updateModels(
            ProviderModelCacheService subject,
            String providerName,
            String scope,
            List<String> models
    ) {
        ProviderModelCacheService.RefreshAttempt attempt = subject.tryBeginRefresh(providerName, scope).orElseThrow();
        assertThat(subject.update(attempt, models)).isTrue();
    }

    private static CodexLocalModelCache.Snapshot codexSnapshot(String... modelIds) {
        return new CodexLocalModelCache.Snapshot(List.of(modelIds), emptyList());
    }

    private static void createSymlinkOrSkip(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.abort("Symbolic links are unavailable: %s".formatted(e.getMessage()));
        }
    }

    private static Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class FailingWriteModelCache extends InMemoryModelCache {
        private boolean deleted;

        @Override
        public boolean writeCache(String providerName, Instant fetchedAt, String scope, List<String> models) {
            return false;
        }

        @Override
        public boolean deleteCache(String providerName) {
            entries.remove(providerName);
            deleted = true;
            return true;
        }
    }

    private static class InMemoryModelCache extends ProviderModelCache {

        protected final Map<String, CacheSnapshot> entries = new HashMap<>();
        private String lastWriteProvider;
        private Instant lastWriteTimestamp;

        private InMemoryModelCache() {
            super(StoragePaths.defaultPaths());
        }

        @Override
        public Optional<CacheSnapshot> readCacheEntry(String providerName) {
            return Optional.ofNullable(entries.get(providerName));
        }

        @Override
        public boolean writeCache(String providerName, Instant fetchedAt, String scope, List<String> models) {
            List<String> storedModels = models == null ? emptyList() : List.copyOf(models);
            entries.put(providerName, new CacheSnapshot(fetchedAt, storedModels, scope));
            lastWriteProvider = providerName;
            lastWriteTimestamp = fetchedAt;
            return true;
        }

        protected void put(String providerName, Instant fetchedAt, List<String> models) {
            entries.put(providerName, new CacheSnapshot(fetchedAt, List.copyOf(models), null));
        }
    }
}
